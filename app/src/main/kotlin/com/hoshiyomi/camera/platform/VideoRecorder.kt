package com.hoshiyomi.camera.platform

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware video recorder: H.264 (MediaCodec surface-input) + AAC audio,
 * muxed into an MP4 — Stargazer's counterpart to the original app's
 * `AsyncMediaRecorder` + `GLDataBufferRecorder` pair, without the OEM's
 * `libNvStreamingSdkCore.so` / `libvskit-ffmpeg.so` prebuilts.
 *
 * The video encoder's input [Surface] is handed to the Camera2 session; the
 * camera writes frames into the codec directly and [drainLoop] pulls the
 * encoded packets into the [MediaMuxer]. Audio is captured from the mic on a
 * dedicated thread and pushed through a second MediaCodec.
 */
class VideoRecorder private constructor(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val videoBitrate: Int,
    private val frameRate: Int,
    private val withAudio: Boolean
) {
    companion object {
        private const val TAG = "VideoRecorder"
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_BITRATE = 128_000
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val I_FRAME_INTERVAL = 2
        private const val TIMEOUT_US = 10_000L

        const val QUALITY_1080P = "1080p"
        const val QUALITY_720P = "720p"

        fun bitRateFor(width: Int, height: Int, fps: Int): Int =
            (width * height * fps * 0.12).toInt().coerceAtLeast(2_000_000)

        /** Creates a recorder writing to [outputFile]; call [start] next. */
        fun create(outputFile: File, quality: String, frameRate: Int, withAudio: Boolean): VideoRecorder {
            val (w, h) = when (quality) {
                QUALITY_720P -> 1280 to 720
                else -> 1920 to 1080
            }
            val bitrate = bitRateFor(w, h, frameRate)
            return VideoRecorder(outputFile, w, h, bitrate, frameRate, withAudio)
        }
    }

    interface Listener {
        fun onStarted()
        fun onFinished(file: File)
        fun onError(throwable: Throwable)
    }

    private var listener: Listener? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var audioRecord: AudioRecord? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private val muxerStarted = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val videoDrain = Thread { drainVideo() }
    private val audioThread = Thread { recordAudio() }

    /** Encoder input surface — a Camera2 capture-session target. */
    var inputSurface: Surface? = null
        private set

    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        this.listener = listener
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            videoCodec = codec

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            running.set(true)
            videoDrain.start()
            if (withAudio) audioThread.start()
            listener.onStarted()
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            release()
            listener.onError(t)
        }
    }

    /** Stops encoding and finalizes the MP4 container. */
    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            videoCodec?.signalEndOfInputStream()
            // Let the drain loop flush remaining packets before we release.
            videoDrain.join(2_000)
            if (audioThread.isAlive) {
                audioThread.interrupt()
                audioThread.join(1_000)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stop join failed", t)
        }
        release()
        listener?.onFinished(outputFile)
    }

    private fun drainVideo() {
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val codec = videoCodec ?: return
                when (val idx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (!running.get()) return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        muxer?.let { m ->
                            videoTrack = m.addTrack(newFormat)
                            maybeStartMuxer()
                        }
                    }
                    else -> {
                        if (idx < 0) return
                        val data: ByteBuffer = codec.getOutputBuffer(idx) ?: return
                        if (bufferInfo.size > 0 && bufferInfo.presentationTimeUs > 0) {
                            data.position(bufferInfo.offset)
                            data.limit(bufferInfo.offset + bufferInfo.size)
                            if (muxerStarted.get() && isSampleFlag(bufferInfo)) {
                                muxer?.writeSampleData(videoTrack, data, bufferInfo)
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "video drain failed", t)
            listener?.onError(t)
        }
    }

    private fun isSampleFlag(info: MediaCodec.BufferInfo): Boolean =
        info.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0

    private fun maybeStartMuxer() {
        // MediaMuxer requires every track added before start(): wait until the
        // video track exists and, when recording with audio, the AAC track too.
        if (muxerStarted.get()) return
        if (videoTrack < 0) return
        if (withAudio && audioTrack < 0) return
        if (muxerStarted.compareAndSet(false, true)) {
            try {
                muxer?.start()
            } catch (t: Throwable) {
                Log.e(TAG, "muxer start failed", t)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordAudio() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val audioRecord = AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            this.audioRecord = audioRecord
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioCodec = codec

            // Wait until the muxer has both tracks before the first AAC packet.
            val buffer = ByteArray(minBuf)
            val info = MediaCodec.BufferInfo()
            audioRecord.startRecording()
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx) ?: continue
                    ib.clear()
                    ib.put(buffer, 0, read)
                    codec.queueInputBuffer(
                        inIdx, 0, read,
                        System.nanoTime() / 1000,
                        if (!running.get()) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    )
                }
                drainAudioOnce(codec, info)
            }
            try {
                audioRecord.stop()
            } catch (t: Throwable) { Log.w(TAG, "audio stop", t) }
            drainAudioOnce(codec, info, flush = true)
        } catch (t: Throwable) {
            Log.e(TAG, "audio thread failed", t)
        }
    }

    private fun drainAudioOnce(codec: MediaCodec, info: MediaCodec.BufferInfo, flush: Boolean = false) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (flush) 100_000L else TIMEOUT_US)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    audioCodec?.outputFormat?.let { f ->
                        muxer?.let { m ->
                            audioTrack = m.addTrack(f)
                            maybeStartMuxer()
                        }
                    }
                }
                idx >= 0 -> {
                    val data = codec.getOutputBuffer(idx) ?: return
                    if (info.size > 0 && muxerStarted.get()) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer?.writeSampleData(audioTrack, data, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun release() {
        try { videoCodec?.stop() } catch (_: Throwable) {}
        try { audioCodec?.stop() } catch (_: Throwable) {}
        try { muxer?.stop() } catch (_: Throwable) {}
        try { muxer?.release() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        try { videoCodec?.release() } catch (_: Throwable) {}
        try { audioCodec?.release() } catch (_: Throwable) {}
        try { inputSurface?.release() } catch (_: Throwable) {}
        videoCodec = null
        audioCodec = null
        muxer = null
        audioRecord = null
        inputSurface = null
    }
}
