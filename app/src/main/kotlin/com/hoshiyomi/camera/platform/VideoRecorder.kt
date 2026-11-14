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
    private val withAudio: Boolean,
    private val bufferInput: Boolean = false
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
        const val QUALITY_480P = "480p"

        fun bitRateFor(width: Int, height: Int, fps: Int): Int =
            (width * height * fps * 0.12).toInt().coerceAtLeast(1_000_000)

        /** Creates a recorder writing to [outputFile]; call [start] next. */
        fun create(
            outputFile: File,
            quality: String,
            frameRate: Int,
            withAudio: Boolean,
            bufferInput: Boolean = false
        ): VideoRecorder {
            val (w, h) = when (quality) {
                QUALITY_480P -> 854 to 480
                QUALITY_720P -> 1280 to 720
                else -> 1920 to 1080
            }
            val bitrate = bitRateFor(w, h, frameRate)
            return VideoRecorder(outputFile, w, h, bitrate, frameRate, withAudio, bufferInput)
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

    /** Encoder input surface — a Camera2 capture-session target (surface mode). */
    var inputSurface: Surface? = null
        private set

    private val frameQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(3)
    private val frameDrain = Thread { drainBufferFrames() }
    @Volatile private var lastBufferPtsUs: Long = 0L

    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        this.listener = listener
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                val color = if (bufferInput) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                } else {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                }
                setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            if (!bufferInput) {
                inputSurface = codec.createInputSurface()
            }
            codec.start()
            videoCodec = codec

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            running.set(true)
            videoDrain.start()
            if (bufferInput) frameDrain.start()
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
            if (!bufferInput) {
                videoCodec?.signalEndOfInputStream()
            } else {
                // Wake frame drain to send EOS.
                frameQueue.offer(ByteArray(0))
                frameDrain.join(2_000)
            }
            videoDrain.join(3_000)
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
        val eosDeadlineNs = java.util.concurrent.atomic.AtomicLong(0L)
        try {
            while (true) {
                val codec = videoCodec ?: return
                val idx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (running.get() || bufferInput) continue
                        val now = System.nanoTime()
                        if (eosDeadlineNs.get() == 0L) {
                            eosDeadlineNs.set(now + 3_000_000_000L)
                        }
                        if (now > eosDeadlineNs.get()) return
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        muxer?.let { m ->
                            if (videoTrack < 0) {
                                videoTrack = m.addTrack(newFormat)
                                maybeStartMuxer()
                            }
                        }
                    }
                    idx >= 0 -> {
                        val data: ByteBuffer? = codec.getOutputBuffer(idx)
                        val doWrite = data != null && bufferInfo.size > 0 && muxerStarted.get() &&
                            videoTrack >= 0 && isSampleFlag(bufferInfo)
                        if (doWrite) {
                            data!!.position(bufferInfo.offset)
                            data.limit(bufferInfo.offset + bufferInfo.size)
                            try { muxer?.writeSampleData(videoTrack, data, bufferInfo) } catch (_: Throwable) {}
                        }
                        try { codec.releaseOutputBuffer(idx, false) } catch (_: Throwable) {}
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> return
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "video drain failed", t)
            listener?.onError(t)
        }
    }

    /** Buffer-input mode: queue one NV12/YUV420 frame (sized for encoder). */
    fun queueNv12Frame(nv12: ByteArray, ptsUs: Long = System.nanoTime() / 1000) {
        if (!bufferInput || !running.get()) return
        lastBufferPtsUs = ptsUs
        frameQueue.poll() // drop oldest to keep latency bounded
        frameQueue.offer(nv12)
    }

    private fun drainBufferFrames() {
        val codec = videoCodec ?: return
        var sentEos = false
        while (running.get() || !frameQueue.isEmpty() || !sentEos) {
            val frame = try {
                frameQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) { null }
            if (frame != null) {
                if (frame.isEmpty()) {
                    queueBufferInput(codec, ByteArray(0), lastBufferPtsUs, true)
                    sentEos = true
                    continue
                }
                val pts = lastBufferPtsUs.takeIf { it > 0 } ?: (System.nanoTime() / 1000)
                queueBufferInput(codec, frame, pts, false)
            } else if (!running.get() && !sentEos) {
                queueBufferInput(codec, ByteArray(0), lastBufferPtsUs, true)
                sentEos = true
            }
        }
    }

    private fun queueBufferInput(codec: MediaCodec, data: ByteArray, ptsUs: Long, endOfStream: Boolean): Boolean {
        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            val inIdx = try { codec.dequeueInputBuffer(TIMEOUT_US) } catch (_: Throwable) { return false }
            if (inIdx >= 0) {
                val ib = codec.getInputBuffer(inIdx) ?: return false
                ib.clear()
                if (data.isNotEmpty()) {
                    val len = kotlin.math.min(data.size, ib.capacity())
                    ib.put(data, 0, len)
                    try { codec.queueInputBuffer(inIdx, 0, len, ptsUs, flags); return true } catch (_: Throwable) { return false }
                } else {
                    try { codec.queueInputBuffer(inIdx, 0, 0, ptsUs, flags); return true } catch (_: Throwable) { return false }
                }
            }
        }
        return false
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
            val bufferSize = (minBuf * 2).coerceAtLeast(4096)
            val recorder = AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            audioRecord = recorder
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioCodec = codec

            val buffer = ByteArray(minBuf.coerceAtLeast(2048))
            val info = MediaCodec.BufferInfo()
            recorder.startRecording()
            var eosSent = false
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val read = try {
                recorder.read(buffer, 0, buffer.size)
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
                -1
            }
                if (read > 0) {
                    queueAudioInput(codec, buffer, read, endOfStream = false)
                } else if (read == AudioRecord.ERROR_BAD_VALUE || read == AudioRecord.ERROR_INVALID_OPERATION) {
                    break
                }
                drainAudioOnce(codec, info)
            }
            if (!eosSent) {
                eosSent = queueAudioInput(codec, ByteArray(0), 0, endOfStream = true)
            }
            try { recorder.stop() } catch (t: Throwable) { Log.w(TAG, "audio stop", t) }
            drainAudioUntilEos(codec, info)
        } catch (t: Throwable) {
            Log.e(TAG, "audio thread failed", t)
        }
    }

    private fun queueAudioInput(
        codec: MediaCodec,
        data: ByteArray,
        size: Int,
        endOfStream: Boolean
    ): Boolean {
        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            val inIdx = try {
                codec.dequeueInputBuffer(TIMEOUT_US)
            } catch (_: Throwable) {
                return false
            }
            if (inIdx >= 0) {
                val ib = codec.getInputBuffer(inIdx) ?: return false
                ib.clear()
                if (size > 0) {
                    ib.put(data, 0, size)
                }
                val pts = System.nanoTime() / 1000
                try {
                    codec.queueInputBuffer(inIdx, 0, size.coerceAtLeast(0), pts, flags)
                    return true
                } catch (_: Throwable) {
                    return false
                }
            }
        }
        return false
    }

    private fun drainAudioUntilEos(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            val idx = try {
                codec.dequeueOutputBuffer(info, 100_000L)
            } catch (_: Throwable) {
                return
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    audioCodec?.outputFormat?.let { f ->
                        muxer?.let { m ->
                            if (audioTrack < 0) {
                                audioTrack = m.addTrack(f)
                                maybeStartMuxer()
                            }
                        }
                    }
                }
                idx >= 0 -> {
                    val data = codec.getOutputBuffer(idx)
                    if (data != null && info.size > 0 && muxerStarted.get() && audioTrack >= 0) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        try { muxer?.writeSampleData(audioTrack, data, info) } catch (_: Throwable) {}
                    }
                    try { codec.releaseOutputBuffer(idx, false) } catch (_: Throwable) {}
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
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
