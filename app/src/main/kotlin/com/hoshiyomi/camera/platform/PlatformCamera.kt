package com.hoshiyomi.camera.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.graphics.SurfaceTexture
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.getSystemService
import com.hoshiyomi.camera.native.NativeCore
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Camera2 engine with vendor-extension probing — the Stargazer counterpart of
 * TCAM's `PlatformCamera2Impl` (4,707 LOC) sitting behind `IPlatformCamera2`.
 *
 * The flow is the original's: open a [CameraDevice] for the logical camera id,
 * probe vendor keys through [VendorKeys], build one capture session over every
 * surface the active mode needs (preview, still-capture ImageReader, and the
 * YUV analysis reader for QR / face detection), and run the repeating request.
 */
class PlatformCamera(private val context: Context) {
    private companion object {
        const val TAG = "PlatformCamera"
        const val JPEG_QUALITY = 100.toByte()
        const val ANALYSIS_W = 640
        const val ANALYSIS_H = 480
        /** TCAM's dump-tolerance gate (`FeatureSupport.isDumpToleranceSupport`). */
        const val RATIO_TOLERANCE = 0.01
        /** `CameraUtil.PICTURE_RATIO_4_3`, the original's standard fallback. */
        val RATIO_4_3 = 4.0 / 3.0
        /** Pixel budget for the processed still path — the project's low-memory
         *  goal; keeps the full-frame RGBA passes (document, beauty, night) at
         *  a size the heap absorbs. */
        const val MAX_CAPTURE_PIXELS = 8_000_000
    }

    data class CameraId(
        val logicalId: String,
        val facing: Int,
        val sensorRotation: Int,
        val isLogical: Boolean,
        val physicalIds: Array<String>
    )

    enum class State { CLOSED, OPENING, READY, CLOSING }
    var state: State = State.CLOSED
        private set

    private val cameraManager: CameraManager =
        context.getSystemService() ?: error("CameraManager unavailable")

    private val backgroundThread = HandlerThread("stargazer-camera").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)
    private val frameCounter = AtomicLong()
    private val analysisFrames = AtomicLong()

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var captureReader: ImageReader? = null
    private var analysisReader: ImageReader? = null
    /** Live filter preview rides the YUV analysis stream (converted through
     *  the Rust core) — an extra RGBA surface is not configurable on most
     *  HALs, so no second reader is allocated. */
    private var filterEnabled = false
    private val filterCallbacks = ConcurrentLinkedQueue<(ByteArray, Int, Int) -> Unit>()
    private var filterRgbaCache: ByteArray? = null
    private var previewSurface: Surface? = null
    /** Chosen camera output size, in the sensor's (landscape) orientation. */
    private var previewBuffer: Size? = null
    /** Aspect the viewfinder holds — the original's per-mode `getTargetRatio()`. */
    private var previewTargetRatio: Double = 4.0 / 3.0

    /** Discovered cameras, sorted back-first like the original's camera list. */
    private var cameras: List<CameraId> = emptyList()
    private var activeIndex = 0

    // Analysis frame delivery
    private val analysisCallbacks = ConcurrentLinkedQueue<(ByteArray, Int, Int) -> Unit>()
    private var analysisWidth = 0
    private var analysisHeight = 0
    private var analysisBufferCache: ByteArray? = null

    fun discover(): List<CameraId> {
        val list = mutableListOf<CameraId>()
        runCatching {
            for (id in cameraManager.cameraIdList) {
                val ch = cameraManager.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                    ?: CameraCharacteristics.LENS_FACING_BACK
                val rotation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val physical = ch.physicalCameraIds.toTypedArray()
                list += CameraId(id, facing, rotation, physical.size > 1, physical)
            }
        }.onFailure { Log.e(TAG, "discover failed", it) }
        cameras = list.sortedBy { it.facing } // BACK=0 first, front next
        return cameras
    }

    fun activeCamera(): CameraId? = cameras.getOrNull(activeIndex)

    fun flip() {
        if (cameras.size < 2) return
        activeIndex = (activeIndex + 1) % cameras.size
    }

    /** Vendor characteristics of the active camera, for capability gating. */
    fun characteristics(): CameraCharacteristics? = runCatching {
        cameraManager.getCameraCharacteristics(checkNotNull(activeCamera()).logicalId)
    }.getOrNull()

    /**
     * Largest JPEG output size — the counterpart of TCAM's `KEY_PICTURE_SIZE`
     * default, whose aspect the viewfinder mirrors (`getTargetRatio()`).
     */
    fun pictureSize(): Size? = characteristics()
        ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(ImageFormat.JPEG)
        ?.maxByOrNull { it.width * it.height }

    /** Aspect of [pictureSize], or the original's standard 4:3 fallback. */
    fun pictureAspectRatio(): Double =
        pictureSize()?.let { it.width.toDouble() / it.height } ?: RATIO_4_3

    /**
     * JPEG still size for the capture reader — the counterpart of TCAM's
     * `KEY_PICTURE_SIZE`: the largest JPEG output whose aspect matches [ratio]
     * (within [RATIO_TOLERANCE]), bounded to [MAX_CAPTURE_PIXELS] so the
     * viewfinder's framing matches the saved file. Falls back to the largest
     * bounded size, then to whatever the sensor offers.
     */
    fun captureSizeFor(ratio: Double): Size? {
        val sizes = characteristics()
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG) ?: return null
        val bounded = sizes.filter { it.width * it.height <= MAX_CAPTURE_PIXELS }
        return bounded.firstOrNull { kotlin.math.abs(it.width.toDouble() / it.height - ratio) <= RATIO_TOLERANCE }
            ?: bounded.maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }
    }

    fun previewTargetRatio(): Double = previewTargetRatio

    /**
     * Sets the viewfinder target aspect — the original's per-mode
     * `getTargetRatio()`. Returns whether it changed: the output buffer is
     * sized at session-configuration time, so a change needs a new session.
     */
    fun setPreviewTargetRatio(ratio: Double): Boolean {
        if (kotlin.math.abs(ratio - previewTargetRatio) < 1e-9) return false
        previewTargetRatio = ratio
        return true
    }

    /**
     * Chooses the preview output size — the port of TCAM's
     * `CameraUtil.findBestMatchPanelSize` (reached through
     * `CommonPhotoMode.getLimitedPreviewSize`): among the SurfaceTexture-class
     * outputs whose aspect is within [RATIO_TOLERANCE] of the target, the
     * smallest at least as tall as the panel's short side, so the viewfinder
     * stays covered without excess bandwidth; nothing matching falls back to
     * standard 4:3, logged exactly like the original.
     */
    fun choosePreviewSize(viewW: Int, viewH: Int, ratio: Double = previewTargetRatio): Size? {
        val map = characteristics()?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val candidates = map.getOutputSizes(SurfaceTexture::class.java)?.toList()
            ?: return null
        // Camera outputs are in the sensor's (landscape) orientation, so the
        // portrait panel's width bounds the buffer's short side.
        val targetShort = minOf(viewW, viewH)
        fun pick(target: Double): Size? = candidates
            .filter { kotlin.math.abs(it.width.toDouble() / it.height - target) <= RATIO_TOLERANCE }
            .minByOrNull { s ->
                val d = s.height - targetShort
                // Prefer a size that covers the panel, then the closest.
                if (d >= 0) d else 1_000_000 - d
            }
        var size = pick(ratio)
        if (size == null) {
            Log.d(TAG, "no preview size match the aspect ratio : $ratio, then use standard 4:3 for preview")
            size = pick(RATIO_4_3)
        }
        Log.d(
            TAG,
            "preview size $size ratio=${"%.4f".format(ratio)} panel=${viewW}x${viewH} candidates=${candidates.size}"
        )
        return size
    }

    /** Display-orientation viewfinder size, for the UI to letterbox to. The
     *  activity is portrait-locked, so a landscape sensor buffer swaps. */
    fun previewDisplaySize(): Size? = previewBuffer?.let { buf ->
        val rotation = activeCamera()?.sensorRotation ?: 0
        if (rotation == 90 || rotation == 270) Size(buf.height, buf.width) else buf
    }

    /** [previewDisplaySize] computed from the characteristics alone, so the UI
     *  can letterbox the view before the camera opens. */
    /** [previewDisplaySize] computed from the characteristics alone, so the UI
     *  can letterbox the view before the camera opens. */
    fun previewDisplaySizeFor(viewW: Int, viewH: Int, ratio: Double): Size? {
        val rotation = activeCamera()?.sensorRotation ?: 0
        val buf = choosePreviewSize(viewW, viewH, ratio) ?: return null
        return if (rotation == 90 || rotation == 270) Size(buf.height, buf.width) else buf
    }

    @SuppressLint("MissingPermission")
    fun open(
        previewTexture: SurfaceTexture,
        viewW: Int,
        viewH: Int,
        captureSize: Size,
        onReady: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        // Ignore double-opens: a new surface can arrive while the previous
        // device close is still in flight, which otherwise surfaces as a fatal
        // ERROR_CAMERA_DEVICE when the second open races the first release.
        if (state == State.OPENING) return
        release()
        val cam = activeCamera() ?: run {
            onError(IllegalStateException("no camera discovered")); return
        }
        state = State.OPENING
        closeReaders()
        // Size the surface buffer before the session is configured: the HAL
        // produces exactly this resolution, and the view letterboxes to the
        // same aspect so the viewfinder never stretches.
        val buf = choosePreviewSize(viewW, viewH)
        if (buf == null) {
            state = State.CLOSED
            onError(IllegalStateException("no preview size for ratio $previewTargetRatio"))
            return
        }
        previewBuffer = buf
        runCatching { previewTexture.setDefaultBufferSize(buf.width, buf.height) }
        previewSurface = Surface(previewTexture)
        captureReader = ImageReader.newInstance(
            captureSize.width, captureSize.height, ImageFormat.JPEG, 4
        ).apply {
            setOnImageAvailableListener({ reader -> onCaptureImage(reader) }, backgroundHandler)
        }
        analysisReader = ImageReader.newInstance(
            ANALYSIS_W, ANALYSIS_H, ImageFormat.YUV_420_888, 4
        ).apply {
            setOnImageAvailableListener({ reader -> onAnalysisImage(reader) }, backgroundHandler)
        }
        try {
            cameraManager.openCamera(
                cam.logicalId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d
                        startSession(onReady, onError)
                    }

                    override fun onDisconnected(d: CameraDevice) {
                        d.close(); device = null; state = State.CLOSED
                    }

                    override fun onError(d: CameraDevice, error: Int) {
                        d.close(); device = null; state = State.CLOSED
                        onError(RuntimeException("camera open failed: $error"))
                    }
                },
                backgroundHandler
            )
        } catch (t: Throwable) {
            state = State.CLOSED
            onError(t)
        }
    }

    private fun startSession(onReady: () -> Unit, onError: (Throwable) -> Unit) {
        val d = device ?: return
        val targets = listOfNotNull(
            previewSurface, captureReader?.surface, analysisReader?.surface
        )
        try {
            d.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        state = State.READY
                        startRepeating()
                        onReady()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        state = State.CLOSED
                        onError(RuntimeException("capture session configure failed"))
                    }
                },
                backgroundHandler
            )
        } catch (t: Throwable) {
            state = State.CLOSED
            onError(t)
        }
    }

    /** Repeating preview request with the vendor-base 3A controls. */
    fun startRepeating() {
        val s = session ?: return
        val d = device ?: return
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let { req.addTarget(it) }
        analysisReader?.surface?.let { req.addTarget(it) }
        req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        recordingSurface?.let { req.addTarget(it) }
        applyVendorPreviewParams(req)
        runCatching { s.setRepeatingRequest(req.build(), repeatingCaptureCallback, backgroundHandler) }
            .onFailure { Log.e(TAG, "repeating request failed", it) }
    }

    /** Active per-mode capture profile (mode intent → controls). */
    private var profile: CaptureProfile = CaptureProfile.Photo

    fun setCaptureProfile(p: CaptureProfile) {
        profile = p
        // Re-issue the repeating request with the new profile so the preview
        // matches what capture will get.
        characteristics()?.let { info ->
            startRepeatingWithProfile(info)
        }
    }

    private fun startRepeatingWithProfile(info: CameraCharacteristics) {
        val s = session ?: return
        val d = device ?: return
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let { req.addTarget(it) }
        recordingSurface?.let { req.addTarget(it) }
        analysisReader?.surface?.let { req.addTarget(it) }
        req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        try {
            profile.applyPreview(req, info)
        } catch (t: Throwable) {
            Log.d(TAG, "profile preview apply degraded: $t")
        }
        applyVendorPreviewParams(req)
        runCatching { s.setRepeatingRequest(req.build(), repeatingCaptureCallback, backgroundHandler) }
    }

    /** Extra vendor params merged into the repeating preview request. */
    private fun applyVendorPreviewParams(req: CaptureRequest.Builder) {
        // Probe-and-write: on HALs without these keys the writes silently
        // become no-ops after the first probe, matching TCAM's degradation.
        VendorKeys.Tran.asdMode.apply(req, 1)
        VendorKeys.Tran.brightnessValue.apply(req, 0)
    }

    /**
     * One-shot still capture. JPEG bytes are handed to [onJpeg]; EXIF is
     * inspected through the Rust core rather than a second decode pass.
     */
    fun capture(onJpeg: (ByteArray) -> Unit, onError: (Throwable) -> Unit) {
        val s = session ?: run { onError(IllegalStateException("session closed")); return }
        val d = device ?: run { onError(IllegalStateException("camera closed")); return }
        val reader = captureReader ?: return
        jpegHandler = onJpeg
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        reader.surface?.let { req.addTarget(it) }
        req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        req.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
        activeCamera()?.sensorRotation?.let { req.set(CaptureRequest.JPEG_ORIENTATION, it) }
        characteristics()?.let { info ->
            try { profile.applyCapture(req, info) } catch (t: Throwable) {
                Log.d(TAG, "profile capture apply degraded: $t")
            }
        }
        applyVendorCaptureParams(req)
        try {
            s.stopRepeating()
            s.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    consumeVendorResults(result)
                    startRepeating()
                }
            }, backgroundHandler)
        } catch (t: Throwable) {
            onError(t)
        }
    }

    /**
     * Burst capture: fires [count] still-capture requests back-to-back and
     * delivers every JPEG as it lands. The Stargazer counterpart of the
     * original's `BurstPMKModeEntry`.
     */
    fun captureBurst(
        count: Int,
        onJpeg: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val s = session ?: run { onError(IllegalStateException("session closed")); return }
        val d = device ?: run { onError(IllegalStateException("camera closed")); return }
        val reader = captureReader ?: return
        burstRemaining.set(count)
        jpegHandler = onJpeg
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        reader.surface?.let { req.addTarget(it) }
        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        req.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
        activeCamera()?.sensorRotation?.let { req.set(CaptureRequest.JPEG_ORIENTATION, it) }
        characteristics()?.let { info ->
            try { profile.applyCapture(req, info) } catch (t: Throwable) {
                Log.d(TAG, "profile burst apply degraded: $t")
            }
        }
        try {
            s.stopRepeating()
            val requests = (1..count).map { req.build() }
            s.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    startRepeating()
                }
            }, backgroundHandler)
        } catch (t: Throwable) {
            onError(t)
        }
    }

    private val burstRemaining = java.util.concurrent.atomic.AtomicInteger(0)

    private var jpegHandler: ((ByteArray) -> Unit)? = null

    private var recordingSurface: Surface? = null

    /**
     * Rebuilds the capture session to include the video encoder's input
     * surface. Recording then rides on the same repeating request as preview;
     * [restorePreviewSession] tears the recorder surface back out.
     */
    /**
     * Rebuilds the capture session to include one or more video encoder
     * surfaces. DualVideo passes two surfaces (main + companion stream) — the
     * same multi-surface session organization TCAM's `CameraDataChannel` uses.
     */
    fun beginVideoRecording(
        vararg recorderSurfaces: Surface,
        onError: (Throwable) -> Unit = { Log.e(TAG, "video session failed", it) }
    ) {
        val s = session ?: run { onError(IllegalStateException("session closed")); return }
        val d = device ?: run { onError(IllegalStateException("camera closed")); return }
        runCatching { s.stopRepeating() }
        val targets = listOfNotNull(previewSurface, *recorderSurfaces, analysisReader?.surface)
        try {
            d.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(sess: CameraCaptureSession) {
                        session = sess
                        recordingSurface = recorderSurfaces.firstOrNull()
                        startRepeating()
                    }

                    override fun onConfigureFailed(sess: CameraCaptureSession) {
                        onError(RuntimeException("record session configure failed"))
                    }
                },
                backgroundHandler
            )
        } catch (t: Throwable) {
            onError(t)
        }
    }

    fun restorePreviewSession() {
        val preview = previewSurface ?: return
        val s = session ?: return
        val d = device ?: return
        runCatching { s.stopRepeating() }
        try {
            d.createCaptureSession(
                listOfNotNull(preview, captureReader?.surface, analysisReader?.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(sess: CameraCaptureSession) {
                        session = sess
                        recordingSurface = null
                        startRepeating()
                    }

                    override fun onConfigureFailed(sess: CameraCaptureSession) {}
                },
                backgroundHandler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "restore preview session failed", t)
        }
    }

    private fun applyVendorCaptureParams(req: CaptureRequest.Builder) {
        VendorKeys.Tran.nightMode.apply(req, 1)
        VendorKeys.Mtk.hdrMode.apply(req, 1)
        VendorKeys.Mtk.zslMode.apply(req, 1)
    }

    /**
     * Scene flags decoded from vendor `CaptureResult` keys — the Stargazer
     * counterpart of TCAM's `SceneResult` / ASD scene broadcasting. Nulls mean
     * the HAL does not define that key (probed NOT_EXIST).
     */
    data class SceneFlags(
        val hdrScene: Int? = null,
        val nightScene: Int? = null,
        val asdScene: Int? = null
    ) {
        /** Human-facing hint for the viewfinder; null when nothing to say. */
        fun hint(): String? = when {
            hdrScene != null && hdrScene > 0 -> "HDR scene"
            nightScene != null && nightScene > 0 -> "Night scene"
            asdScene != null && asdScene > 0 -> "Auto scene"
            else -> null
        }
    }

    private val sceneCallbacks = ConcurrentLinkedQueue<(SceneFlags) -> Unit>()

    /** Subscribe to vendor scene-flag updates (ASD / MagicSky / night). */
    fun addSceneCallback(cb: (SceneFlags) -> Unit) {
        sceneCallbacks.add(cb)
    }

    fun removeSceneCallback(cb: (SceneFlags) -> Unit) {
        sceneCallbacks.remove(cb)
    }

    private val repeatingCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            r: CaptureRequest,
            result: TotalCaptureResult
        ) {
            consumeVendorResults(result)
        }
    }

    private fun consumeVendorResults(result: CaptureResult) {
        // Reading probed vendor result keys drives feature state; values are
        // null (and marked NOT_EXIST) on HALs without them.
        val flags = SceneFlags(
            hdrScene = VendorKeys.Tran.hdrSceneResult.read(result),
            nightScene = VendorKeys.Tran.nightSceneResult.read(result),
            asdScene = VendorKeys.Tran.asdSceneResult.read(result)
        )
        for (cb in sceneCallbacks) cb(flags)
    }

    private fun onCaptureImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            image.close()
            jpegHandler?.let { handler ->
                if (burstRemaining.get() == 0) {
                    jpegHandler = null
                } else if (burstRemaining.decrementAndGet() == 0) {
                    jpegHandler = null
                }
                // Orientation / make-model come straight through the Rust EXIF
                // parser — no Bitmap and no second allocation.
                val orientation = NativeCore.exifOrientation(bytes)
                Log.d(TAG, "captured jpeg, exif orientation=$orientation")
                handler(bytes)
            }
        } catch (t: Throwable) {
            image.close()
            Log.e(TAG, "jpeg read failed", t)
        }
    }

    /** Subscribe to the low-res RGBA filter-preview stream. */
    fun addFilterCallback(cb: (rgba: ByteArray, w: Int, h: Int) -> Unit) {
        filterCallbacks.add(cb)
    }

    fun removeFilterCallback(cb: (rgba: ByteArray, w: Int, h: Int) -> Unit) {
        filterCallbacks.remove(cb)
    }

    /**
     * Gates processing of the live-filter RGBA stream. The reader stays a
     * session target either way — flipping only the delivery, so no session
     * rebuild (and none of the open-churn risk that comes with it).
     */
    fun enableFilterPreview(enabled: Boolean) {
        Log.d(TAG, "enableFilterPreview($enabled)")
        filterEnabled = enabled
    }

    fun isFilterPreviewEnabled(): Boolean = filterEnabled

    /** Subscribe to low-res YUV frames for QR / face detection. */
    fun addAnalysisCallback(cb: (y: ByteArray, w: Int, h: Int) -> Unit) {
        analysisCallbacks.add(cb)
    }

    fun removeAnalysisCallback(cb: (y: ByteArray, w: Int, h: Int) -> Unit) {
        analysisCallbacks.remove(cb)
    }

    private fun onAnalysisImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val w = image.width
        val h = image.height
        val n = analysisFrames.incrementAndGet()
        if (n % 60 == 1L) {
            Log.d(TAG, "analysis frame #$n ${w}x${h} filter=$filterEnabled cbs=${filterCallbacks.size}")
        }
        if (analysisWidth != w || analysisHeight != h) {
            analysisWidth = w; analysisHeight = h
            analysisBufferCache = ByteArray(w * h)
        }
        try {
            val yPlane = image.planes[0]
            val y = planeToBytes(yPlane.buffer, yPlane.rowStride, w, h)
            // Live-filter pipeline: convert the same YUV frame to RGBA through
            // the Rust core and deliver it to the filter callbacks.
            if (filterEnabled && filterCallbacks.isNotEmpty()) {
                runCatching {
                    val uPlane = image.planes[1]
                    val vPlane = image.planes[2]
                    // Copy planes raw — padding and pixel stride must survive
                    // so the Rust kernel indexes them exactly as the HAL lays
                    // them out (planar or interleaved).
                    val u = rawPlane(uPlane.buffer)
                    val v = rawPlane(vPlane.buffer)
                    if (n % 60 == 1L) {
                        Log.d(
                            TAG,
                            "plane geom: yStride=${yPlane.rowStride} yLen=${y.size} " +
                                "uStride=${uPlane.rowStride} uPx=${uPlane.pixelStride} uLen=${u.size} " +
                                "vStride=${vPlane.rowStride} vPx=${vPlane.pixelStride} vLen=${v.size}"
                        )
                    }
                    if (filterRgbaCache?.size != w * h * 4) filterRgbaCache = ByteArray(w * h * 4)
                    val rgba = filterRgbaCache!!
                    NativeCore.yuv420ToRgba(
                        w, h,
                        y, yPlane.rowStride,
                        u, uPlane.rowStride, uPlane.pixelStride,
                        v, vPlane.rowStride, vPlane.pixelStride,
                        rgba
                    )
                    for (cb in filterCallbacks) cb(rgba, w, h)
                }.onFailure { Log.w(TAG, "filter rgba path failed: $it") }
            }
            image.close()
            for (cb in analysisCallbacks) cb(y, w, h)
        } catch (t: Throwable) {
            image.close()
        }
    }

    /** Copies a whole Image plane, padding included. */
    private fun rawPlane(buffer: ByteBuffer): ByteArray {
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    private fun planeToBytes(buffer: ByteBuffer, rowStride: Int, w: Int, h: Int): ByteArray {
        val out = analysisBufferCache ?: ByteArray(w * h).also { analysisBufferCache = it }
        var pos = 0
        for (row in 0 until h) {
            buffer.position(row * rowStride)
            buffer.get(out, pos, w)
            pos += w
        }
        return out
    }

    /**
     * Face-weighted exposure compensation: [bias] in [-1, 1] maps onto the
     * sensor's AE compensation steps. 0 resets to no compensation. This is the
     * Stargazer counterpart of the original's `enableFace3A` + face-AE logic.
     */
    /**
     * Manual ("Pro") exposure/focus overrides — the Stargazer counterpart of
     * the original's `ProfessionalMode` + `captureParams`. Passing null for a
     * field relinquishes it back to auto.
     */
    data class ManualControls(
        val iso: Int? = null,
        val exposureTimeNs: Long? = null,
        val wbMode: Int? = null,
        val focusDistance: Float? = null
    )

    /** Supported sensitivity range for the Pro ISO dial. */
    fun supportedIsoRange(): IntArray? =
        characteristics()?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?.let { intArrayOf(it.lower, it.upper) }

    /** Supported exposure-time range for the Pro shutter dial. */
    fun supportedExposureRange(): LongArray? =
        characteristics()?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?.let { longArrayOf(it.lower, it.upper) }

    fun minFocusDistance(): Float? =
        characteristics()?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)

    /** Applies [controls] to the repeating request; nulls revert to auto. */
    fun setManualControls(controls: ManualControls) {
        val s = session ?: return
        val d = device ?: return
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let { req.addTarget(it) }
        recordingSurface?.let { req.addTarget(it) }
        analysisReader?.surface?.let { req.addTarget(it) }
        if (controls.iso != null || controls.exposureTimeNs != null) {
            // Manual exposure needs AE off; the app owns the two 3A fields.
            req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            controls.iso?.let {
                req.set(CaptureRequest.SENSOR_SENSITIVITY, it)
            }
            controls.exposureTimeNs?.let {
                req.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
            }
        } else {
            req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
        controls.wbMode?.let {
            req.set(CaptureRequest.CONTROL_AWB_MODE, it)
        } ?: req.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        controls.focusDistance?.let { fd ->
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            req.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd)
        } ?: req.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        runCatching { s.setRepeatingRequest(req.build(), repeatingCaptureCallback, backgroundHandler) }
            .onFailure { Log.e(TAG, "manual controls failed", it) }
    }

    fun setExposureBias(bias: Float) {
        val s = session ?: return
        val d = device ?: return
        val ch = characteristics() ?: return
        val range = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        // Range is symmetric (e.g. -6..6); magnitude is the max step count.
        val maxSteps = kotlin.math.abs(range?.lower?.toFloat() ?: 0f)
        if (maxSteps <= 0f || step == null) return
        val steps = (bias * maxSteps).toInt()
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let { req.addTarget(it) }
        recordingSurface?.let { req.addTarget(it) }
        analysisReader?.surface?.let { req.addTarget(it) }
        req.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
        req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        runCatching { s.setRepeatingRequest(req.build(), repeatingCaptureCallback, backgroundHandler) }
            .onFailure { Log.e(TAG, "exposure bias failed", it) }
    }

    fun zoom(ratio: Float) {
        val s = session ?: return
        val d = device ?: return
        val ch = characteristics() ?: return
        val max = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
        val r = ratio.coerceIn(1f, max.coerceAtLeast(1f))
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let { req.addTarget(it) }
        analysisReader?.surface?.let { req.addTarget(it) }
        req.set(CaptureRequest.CONTROL_ZOOM_RATIO, r)
        recordingSurface?.let { req.addTarget(it) }
        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        runCatching { s.setRepeatingRequest(req.build(), repeatingCaptureCallback, backgroundHandler) }
    }

    fun release() {
        // Called on every open() as the churn guard; keep it compact so the
        // log stays readable. The full stack trace only mattered while the
        // double-open race was being diagnosed.
        Log.d(TAG, "release() state=$state")
        state = State.CLOSING
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        closeReaders()
        state = State.CLOSED
    }

    fun frameCount(): Long = frameCounter.get()

    private fun closeReaders() {
        runCatching { captureReader?.close() }
        runCatching { analysisReader?.close() }
        captureReader = null
        analysisReader = null
    }
}
