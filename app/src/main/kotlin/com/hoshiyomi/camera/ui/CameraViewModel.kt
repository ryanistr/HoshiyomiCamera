package com.hoshiyomi.camera.ui

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoshiyomi.camera.core.MediaSaver
import com.hoshiyomi.camera.mode.CameraMode
import com.hoshiyomi.camera.mode.ModeRegistry
import com.hoshiyomi.camera.native.NativeCore
import com.hoshiyomi.camera.platform.PlatformCamera
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Screen state for the camera — one immutable snapshot the UI renders from. */
data class CameraUiState(
    val modes: List<CameraMode> = ModeRegistry.backModes,
    val activeMode: CameraMode = ModeRegistry.photo,
    val facingFront: Boolean = false,
    val ready: Boolean = false,
    val isCapturing: Boolean = false,
    val isRecording: Boolean = false,
    val lastQr: String? = null,
    /** Faces in normalized viewfinder coords: [x, y, w, h] per face. */
    val faces: List<FloatArray> = emptyList(),
    /** Exposure bias from face metering, in [-1, 1]. */
    val meteringBias: Float = 0f,
    /** Pro-mode manual control state (0 at a dial = AUTO). */
    val isoRange: IntArray? = null,
    val shutterRange: LongArray? = null,
    val focusMin: Float = 0f,
    val proIso: Float = 0f,
    val proShutter: Float = 0f,
    val proFocus: Float = 0f,
    /** Live filter-preview thumbnail (low-res RGBA → Bitmap). */
    val filterPreview: android.graphics.Bitmap? = null,
    /** Active color-filter style name, when live preview filtering is on. */
    val filterStyle: String? = null,
    /** Scene hint driven by vendor CaptureResult keys (ASD/MagicSky/Night). */
    val sceneHint: String? = null,
    /** Panorama frames captured / total, for the live pan guide. */
    val panoProgress: Int = 0,
    /** Launched-for-result capture (the reference camera app IntentPhotoMode/IntentVideoMode). */
    val isIntentMode: Boolean = false,
    /** Captured-but-not-yet-confirmed still, shown as the review image. */
    val intentReview: android.graphics.Bitmap? = null
)

/**
 * Owns the [PlatformCamera] lifetime and wires preview surface, captures and
 * analysis frames (QR decode through the Rust core) to the UI state.
 */
class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "CameraVM"
        const val JPEG_QUALITY_KT = 100
        const val PANO_FRAME_COUNT = 3
        const val PANO_MAX_W = 1080
        const val BURST_COUNT = 5
        const val TIMELAPSE_FRAMES = 8
        const val TIMELAPSE_INTERVAL_MS = 2500L
        const val DOC_W = 1080
        const val SLOW_MOTION_FPS = 120
        const val NIGHT_FRAME_COUNT = 4
        const val NIGHT_W = 720
    }

    /** Fallback JPEG still size; normally [PlatformCamera.captureSizeFor]
     *  supplies one matching the viewfinder ratio. */
    private val FALLBACK_CAPTURE_SIZE = android.util.Size(1920, 1080)

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private val camera = PlatformCamera(app)
    private val mediaSaver = MediaSaver(app)
    private var previewTexture: android.graphics.SurfaceTexture? = null
    private var previewViewSize = android.util.Size(0, 0)
    private var attached = false

    /** Viewfinder aspect for [mode] — the reference camera app's `getTargetRatio()`: photo modes
     *  mirror the largest picture size, video modes the recording aspect. */
    private fun targetRatioForMode(mode: com.hoshiyomi.camera.mode.CameraMode): Double =
        if (mode.isVideo) 16.0 / 9.0 else camera.pictureAspectRatio()

    /** Google Play Services for AR is the AR mode's platform component; the
     *  chip surfaces only where it is installed (the original gates AR the
     *  same way via its resource flags). */
    private val arAvailable: Boolean = runCatching {
        app.packageManager.getPackageInfo("com.google.ar.core", 0)
        true
    }.getOrDefault(false)

    private fun refreshModes() {
        _state.update {
            it.copy(modes = ModeRegistry.modesFor(it.facingFront)
                .filter { m -> m.id != "arspace" || arAvailable })
        }
    }

    /** Viewfinder width/height in the display orientation — the exact buffer
     *  ratio when the camera has one, else the inverted mode target (the
     *  buffer is sensor-oriented). Drives the letterbox so the view matches
     *  the HAL output's aspect instead of stretching. */
    fun previewDisplayRatio(): Float =
        camera.previewDisplaySize()?.let { it.width.toFloat() / it.height }
            ?: (1f / targetRatioForMode(state.value.activeMode).toFloat())

    private val qrCallback = { y: ByteArray, w: Int, h: Int -> onAnalysisFrame(y, w, h) }

    /** Live filter preview: consumes the low-res RGBA stream and runs it back
     *  through the Rust imgproc/beauty pipeline at a throttled rate. */
    private val filterCallback = { rgba: ByteArray, w: Int, h: Int ->
        onFilterFrame(rgba, w, h)
    }

    private val sceneCallback = { flags: com.hoshiyomi.camera.platform.PlatformCamera.SceneFlags ->
        onSceneFlags(flags)
    }

    init {
        NativeCore.ensureLoaded()
        camera.discover()
        camera.addAnalysisCallback(qrCallback)
        camera.addFilterCallback(filterCallback)
        camera.addSceneCallback(sceneCallback)
    }

    fun start() {
        if (state.value.ready || attached && previewTexture != null) {
            refreshModes()
        }
    }

    /**
     * Camera-visibility gate: CameraService gives a non-trusted uid only a few
     * seconds of camera grace while its task is still animating in, and a
     * system overlay can hold window focus even for a resumed activity. The
     * robust anchor is the activity's RESUMED state: opening then (and retrying
     * once if the grace window expires mid-transition) keeps the session alive.
     */
    private var resumed = false

    private var pendingStartMode: String? = null

    /** Mode deep-link requested before the camera was ready; applied on open. */
    fun queueModeOnStart(modeId: String) {
        Log.d(TAG, "queueModeOnStart($modeId) state=${camera.state}")
        if (camera.state == com.hoshiyomi.camera.platform.PlatformCamera.State.READY) {
            selectMode(modeId)
        } else {
            pendingStartMode = modeId
        }
    }

    fun onResume() {
        resumed = true
        maybeOpen()
        pendingStartMode?.let { modeId ->
            if (state.value.ready) {
                selectMode(modeId)
                pendingStartMode = null
            }
        }
    }

    fun onPause() {
        resumed = false
    }

    // ---- Panorama capture ----
    private val panoFrames = mutableListOf<ByteArray>()   // RGBA frames
    private val panoGreys = mutableListOf<ByteArray>()    // matching grey for dx
    private var panoW = 0
    private var panoH = 0
    private var panoCapturing = false
    /** Last accepted analysis-frame grey — the panorama motion reference. */
    private var panoRefGrey: ByteArray? = null
    /** True once a frame is captured and the next is waiting on pan motion. */
    private var panoArmed = false
    private var panoTick = 0

    /**
     * Panorama flow, mirroring the original's PanoramaModeEntry capture
     * sequence: snap N stills, align each pair through the Rust core
     * ([estimateDx]), blend them all onto one canvas
     * ([stitchHorizontal]) and save the composite.
     */
    private fun capturePanoramaFrame() {
        if (panoCapturing && panoFrames.size >= PANO_FRAME_COUNT) return
        panoCapturing = true
        camera.capture(
            onJpeg = { jpeg ->
                viewModelScope.launch(Dispatchers.IO) {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        ?: run { _state.update { it.copy(isCapturing = false) }; return@launch }
                    val w = bmp.width
                    val h = bmp.height
                    if (panoW == 0) { panoW = w; panoH = h }
                    // Downscale very large stills so the stitch canvas stays bounded.
                    val (rw, rh) = if (w > PANO_MAX_W) {
                        PANO_MAX_W to (h * PANO_MAX_W / w)
                    } else w to h
                    val scaled = if (rw != w) android.graphics.Bitmap.createScaledBitmap(bmp, rw, rh, true) else bmp
                    if (scaled !== bmp) bmp.recycle()
                    val pixels = IntArray(rw * rh)
                    scaled.getPixels(pixels, 0, rw, 0, 0, rw, rh)
                    val rgba = ByteArray(rw * rh * 4)
                    val grey = ByteArray(rw * rh)
                    for (i in pixels.indices) {
                        val c = pixels[i]
                        val r = (c shr 16) and 0xff
                        val g = (c shr 8) and 0xff
                        val b = c and 0xff
                        rgba[i * 4] = r.toByte(); rgba[i * 4 + 1] = g.toByte()
                        rgba[i * 4 + 2] = b.toByte(); rgba[i * 4 + 3] = 255.toByte()
                        grey[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
                    }
                    if (panoW != rw) panoW = rw; panoH = rh
                    panoFrames.add(rgba)
                    panoGreys.add(grey)
                    scaled.recycle()
                    _state.update { it.copy(panoProgress = panoFrames.size) }
                    if (panoFrames.size >= PANO_FRAME_COUNT) {
                        finishPanorama()
                    } else {
                        // Arm the motion gate: the next panorama frame is
                        // captured only once the user pans far enough.
                        panoArmed = true
                    }
                }
            },
            onError = { t ->
                Log.e(TAG, "panorama frame failed", t)
                panoFrames.clear(); panoGreys.clear()
                _state.update { it.copy(isCapturing = false) }
            }
        )
    }

    private fun finishPanorama() {
        if (panoFrames.size < 2) {
            _state.update { it.copy(isCapturing = false) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val n = panoFrames.size
                val dxs = IntArray(n - 1) { i ->
                    com.hoshiyomi.camera.native.NativeCore.estimateDx(
                        panoGreys[i], panoGreys[i + 1], panoW, panoH, panoW / 4
                    )
                }
                val dw = panoW * n
                val out = ByteArray(dw * panoH * 4)
                val canvasW = com.hoshiyomi.camera.native.NativeCore.stitchHorizontal(
                    panoFrames.toTypedArray(), panoW, panoH, 4, dxs, out
                )
                val pixels = IntArray(canvasW * panoH)
                for (i in pixels.indices) {
                    pixels[i] = (0xff shl 24) or
                        ((out[i * 4].toInt() and 0xff) shl 16) or
                        ((out[i * 4 + 1].toInt() and 0xff) shl 8) or
                        (out[i * 4 + 2].toInt() and 0xff)
                }
                val bmp = android.graphics.Bitmap.createBitmap(canvasW, panoH, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.setPixels(pixels, 0, canvasW, 0, 0, canvasW, panoH)
                val bos = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, bos)
                bmp.recycle()
                val jpeg = bos.toByteArray()
                val withExif = writeExif(jpeg, "panorama")
                val uri = mediaSaver.saveJpeg(withExif, displayName("panorama"))
                Log.d(TAG, "panorama saved: $uri canvasW=$canvasW dxs=${dxs.toList()}")
            } catch (t: Throwable) {
                Log.e(TAG, "panorama stitch failed", t)
            } finally {
                panoFrames.clear(); panoGreys.clear(); panoCapturing = false
                panoRefGrey = null; panoArmed = false
                _state.update { it.copy(isCapturing = false, panoProgress = 0) }
            }
        }
    }

    private var openInFlight = false

    // ---- Intent capture contract (the reference camera app IntentPhotoMode / IntentASDMode /
    // IntentVideoMode): CaptureActivity launches us with `output`/`return-data`;
    // the still is reviewed, then attached — written to the caller's URI,
    // forwarded to the crop flow, or returned inline as a bitmap. ----

    /** Result contract events consumed by [com.hoshiyomi.camera.CaptureActivity]. */
    data class IntentResult(val ok: Boolean, val data: android.content.Intent? = null)

    private val _intentResults = kotlinx.coroutines.flow.MutableSharedFlow<IntentResult>(extraBufferCapacity = 8)
    val intentResults: kotlinx.coroutines.flow.SharedFlow<IntentResult> = _intentResults

    @Volatile private var intentOutput: android.net.Uri? = null
    @Volatile private var intentInline = false
    @Volatile private var intentCrop: String? = null
    @Volatile private var intentJpeg: ByteArray? = null

    /** Faithful to `IntentPhotoMode.MAX_NUMBER_PIXELS` for the inline bitmap. */
    private val intentMaxPixels = 51200

    /** Arms the intent contract; [modeId] is photo/asd for stills, video for video. */
    fun setupIntentCapture(modeId: String, output: android.net.Uri?, inline: Boolean, crop: String?) {
        intentOutput = output
        intentInline = inline
        intentCrop = crop
        intentJpeg = null
        _state.update { it.copy(isIntentMode = true, intentReview = null) }
        queueModeOnStart(modeId)
    }

    /** Back / caller cancels: RESULT_CANCELED, the framework's default result. */
    fun cancelIntentCapture() {
        intentJpeg = null
        _state.update { it.copy(isIntentMode = false, intentReview = null) }
        viewModelScope.launch { _intentResults.emit(IntentResult(false)) }
    }

    /** ✗ on the review image — go back to the viewfinder. */
    fun retakeIntentCapture() {
        intentJpeg = null
        _state.update { it.copy(intentReview = null) }
    }

    /** ✓ on the review image — run the attach path and hand the result back. */
    fun confirmIntentCapture() {
        val jpeg = intentJpeg
        viewModelScope.launch {
            if (jpeg == null) {
                _intentResults.emit(IntentResult(false))
                return@launch
            }
            val result = runCatching { attachIntentJpeg(jpeg) }
                .getOrElse { Log.e(TAG, "intent attach failed", it); IntentResult(false) }
            intentJpeg = null
            _state.update { it.copy(isIntentMode = false, intentReview = null) }
            _intentResults.emit(result)
        }
    }

    /** `doAttach()` port: crop forward → save-URI → inline bitmap. */
    private fun attachIntentJpeg(jpeg: ByteArray): IntentResult {
        val crop = intentCrop
        if (crop != null) {
            // the reference camera app's attachToCropIntent: temp file → com.android.camera.action.CROP
            // with output or return-data (+ circleCrop); the Activity forwards it.
            val ctx = getApplication<Application>()
            val tmp = java.io.File(ctx.cacheDir, "intent_crop.jpg")
            tmp.outputStream().use { it.write(jpeg) }
            val tmpUri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", tmp
            )
            val fwd = android.content.Intent("com.android.camera.action.CROP").apply {
                data = tmpUri
                putExtra("crop", crop)
                if (crop.equals("circle", ignoreCase = true)) {
                    putExtra("circleCrop", true)
                }
                if (intentOutput != null) {
                    putExtra("output", intentOutput)
                } else {
                    putExtra("return-data", true)
                }
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return IntentResult(true, fwd)
        }
        val out = intentOutput
        if (out != null) {
            // attachToSaveUri: stream the JPEG into the caller's URI.
            getApplication<Application>().contentResolver.openOutputStream(out)?.use { os ->
                os.write(jpeg)
            } ?: return IntentResult(false)
            return IntentResult(true, null)
        }
        // attachToInLinedIntent: downscaled bitmap in extras "data".
        val bmp = inlineIntentBitmap(jpeg) ?: return IntentResult(false)
        val data = android.content.Intent("inline-data").apply {
            putExtra("data", bmp)
        }
        return IntentResult(true, data)
    }

    /** Decodes ≤ 51200 px and bakes the EXIF orientation in, like
     *  `BitmapUtils.rotateAndMirror(..., MAX_NUMBER_PIXELS, ...)` in the reference camera app. */
    private fun inlineIntentBitmap(jpeg: ByteArray): android.graphics.Bitmap? {
        val orientation = runCatching { NativeCore.exifOrientation(jpeg) }.getOrDefault(0)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var s = 1
        while ((bounds.outWidth / s) * (bounds.outHeight / s) > intentMaxPixels) s *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = s
        }
        val small = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: return null
        val rotation = when (orientation) {
            90 -> 90f; 180 -> 180f; 270 -> 270f; else -> 0f
        }
        val out = if (rotation != 0f) {
            val m = android.graphics.Matrix().apply { postRotate(rotation) }
            android.graphics.Bitmap.createBitmap(small, 0, 0, small.width, small.height, m, true)
                .also { if (it !== small) small.recycle() }
        } else small
        return out
    }

    /** Review thumbnail for the intent still — fit the viewfinder. */
    private fun intentReviewBitmap(jpeg: ByteArray): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var s = 1
        while (bounds.outWidth / (s * 2) >= 1080) s *= 2
        Log.d(TAG, "intent review: bytes=${jpeg.size} out=${bounds.outWidth}x${bounds.outHeight} sample=$s")
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = s
            inJustDecodeBounds = false
        }
        return android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }

    /** Shutter press while in intent mode: capture and show the review. */
    private fun captureIntentStill() {
        _state.update { it.copy(isCapturing = true) }
        camera.capture(
            onJpeg = { jpeg ->
                viewModelScope.launch(Dispatchers.IO) {
                    val processed = postProcessCapture(jpeg, "photo")
                    val withExif = writeExif(processed, "photo")
                    intentJpeg = withExif
                    runCatching {
                        val review = intentReviewBitmap(withExif)
                        Log.d(TAG, "intent review bitmap: ${review?.let { "${it.width}x${it.height}" } ?: "null"}")
                        _state.update { it.copy(isCapturing = false, intentReview = review) }
                    }.onFailure { t ->
                        Log.e(TAG, "intent review failed", t)
                        _state.update { it.copy(isCapturing = false) }
                    }
                }
            },
            onError = { t ->
                Log.e(TAG, "intent capture failed", t)
                _state.update { it.copy(isCapturing = false) }
                viewModelScope.launch { _intentResults.emit(IntentResult(false)) }
            }
        )
    }

    /** IntentVideoMode.attachToSaveUri: deliver the recording to the caller. */
    private suspend fun attachIntentVideo(savedFile: java.io.File): IntentResult {
        val out = intentOutput
        return if (out != null) {
            getApplication<Application>().contentResolver.openOutputStream(out)?.use { os ->
                savedFile.inputStream().use { it.copyTo(os) }
            } ?: return IntentResult(false)
            savedFile.delete()
            IntentResult(true, null)
        } else {
            val uri = mediaSaver.saveVideo(savedFile) ?: return IntentResult(false)
            IntentResult(true, android.content.Intent().apply {
                data = uri
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    /** Quality/Aperture-style settings the VM needs from outside the composable:
     *  the settings sheet reports selections here. */
    private val settingSelections = mutableMapOf<String, Int>()

    /** Per-mode settings applied from the settings sheet (Metering, TCM mode). */
    fun onSettingSelected(key: String, index: Int) {
        settingSelections[key] = index
        when (key) {
            "metering" -> camera.setMeteringMode(listOf("auto", "center", "spot", "average")[index])
            "tcmode" -> camera.setTranssionCameraMode(index == 1)
        }
    }

    private fun maybeOpen() {
        if (resumed && previewTexture != null && !state.value.ready && !openInFlight) {
            openCamera(previewTexture!!, previewViewSize)
        }
    }

    /** Every (re)open funnels through here: flip, mode-ratio change, and the
     *  ERROR_CAMERA_DEVICE retry all land on the same session plumbing. */
    private fun openCamera(
        texture: android.graphics.SurfaceTexture,
        viewSize: android.util.Size
    ) {
        openInFlight = true
        camera.setPreviewTargetRatio(targetRatioForMode(state.value.activeMode))
        // Still size follows the viewfinder's ratio so the framing seen is the
        // framing saved — the original's KEY_PICTURE_SIZE pairing.
        val captureSize = camera.captureSizeFor(targetRatioForMode(state.value.activeMode))
            ?: FALLBACK_CAPTURE_SIZE
        camera.open(
            previewTexture = texture,
            viewW = viewSize.width,
            viewH = viewSize.height,
            captureSize = captureSize,
            onReady = {
                Log.d(TAG, "camera ready: ${camera.activeCamera()}")
                openInFlight = false
                _state.update {
                    it.copy(
                        ready = true,
                        isoRange = camera.supportedIsoRange(),
                        shutterRange = camera.supportedExposureRange(),
                        focusMin = camera.minFocusDistance() ?: 0f
                    )
                }
                pendingStartMode?.let { modeId ->
                    selectMode(modeId)
                    pendingStartMode = null
                }
            },
            onError = { t ->
                Log.e(TAG, "camera error", t)
                openInFlight = false
                _state.update { it.copy(ready = false) }
                // ERROR_CAMERA_DEVICE (3) arrives when the uid-grace window
                // expired during the activity open transition; a single
                // retry after a cool-down re-establishes the session once
                // visibility has landed.
                if (t is RuntimeException && t.message?.contains("failed: 3") == true && resumed) {
                    retryOnce()
                }
            }
        )
    }

    private var retryUsed = false

    private fun retryOnce() {
        if (retryUsed) return
        retryUsed = true
        viewModelScope.launch {
            delay(1200)
            if (resumed && previewTexture != null && !state.value.ready) {
                openCamera(previewTexture!!, previewViewSize)
            }
        }
    }

    private var attachCount = 0

    fun attachPreview(texture: android.graphics.SurfaceTexture, viewW: Int, viewH: Int) {
        attachCount++
        Log.d(TAG, "attachPreview #$attachCount ${viewW}x${viewH} (ready=${state.value.ready}, opening=${camera.state})")
        previewTexture = texture
        previewViewSize = android.util.Size(viewW, viewH)
        attached = true
        maybeOpen()
    }

    fun previewSizeChanged(viewW: Int, viewH: Int) {
        if (viewW > 0 && viewH > 0) previewViewSize = android.util.Size(viewW, viewH)
    }

    fun detachPreview() {
        attached = false
        camera.release()
        _state.update { it.copy(ready = false) }
    }

    /**
     * True only when we moved to the front sensor *automatically* because a
     * front-only mode (WideSelfie / STBlur) was selected. Leaving that mode
     * for a normal mode restores the back sensor as the default. A user's
     * manual flip clears it, so an intentional front-camera choice is kept.
     */
    private var autoFlippedToFront = false

    fun flipCamera() {
        // A manual flip is an explicit facing choice — never auto-restore.
        autoFlippedToFront = false
        camera.release()
        _state.update {
            val facing = !it.facingFront
            it.copy(
                facingFront = facing,
                modes = ModeRegistry.modesFor(facing),
                activeMode = ModeRegistry.modesFor(facing).firstOrNull { m -> m.id == it.activeMode.id }
                    ?: ModeRegistry.photo,
                ready = false
            )
        }
        camera.flip()
        // The front sensor may expose a different picture ratio, so the
        // viewfinder aspect is re-derived inside openCamera.
        previewTexture?.let { openCamera(it, previewViewSize) }
    }

    fun selectMode(id: String) {
        val mode = ModeRegistry.modeById(id)
        Log.d(TAG, "selectMode($id) found=${mode != null}")
        if (mode == null) return
        // Live filtering belongs to the photo-family modes; leaving them
        // releases the preview stream.
        if (id != "photo" && id != "portrait" && state.value.filterStyle != null) {
            setFilterStyle(null)
        }
        _state.update { it.copy(activeMode = mode) }
        if (id != "professional") resetProControls()
        // Map mode intent onto its capture profile (vendor keys degrade to
        // no-ops on unsupported HALs, exactly like the original's key probing).
        characteristicsInfo()?.let { info ->
            camera.setCaptureProfile(com.hoshiyomi.camera.platform.CaptureProfile.forMode(id, info))
        }
        if (id == "arspace") {
            // ARCore owns the camera for this mode: hand it over now so its
            // session opens cleanly. Leaving the mode rebuilds the Camera2
            // session through the viewfinder's attach path.
            camera.release()
            _state.update { it.copy(ready = false) }
            return
        }
        // Front-only modes (the original's WideSelfie / STBlur): switch to the
        // front sensor when selected from the back camera. flipCamera reopens
        // with the profile and ratio already applied above.
        val needsFront = id == "wideselfie" || id == "stblur"
        if (needsFront && !state.value.facingFront) {
            flipCamera()                    // clears the manual flag
            autoFlippedToFront = true        // mark this front switch as automatic
        } else if (!needsFront && autoFlippedToFront && state.value.facingFront) {
            // Returning from a front-only mode to a normal mode: restore the
            // back camera as the default instead of staying on front.
            Log.d(TAG, "selectMode($id): restoring back camera after front-only mode")
            autoFlippedToFront = false
            flipCamera()
        }
        // the reference camera app switches the viewfinder aspect with the mode (4:3 photo family,
        // 16:9 video family); the buffer is sized at session-configuration
        // time, so a ratio change rebuilds the session against the same surface.
        if (state.value.ready && camera.setPreviewTargetRatio(targetRatioForMode(mode))) {
            _state.update { it.copy(ready = false) }
            previewTexture?.let { openCamera(it, previewViewSize) }
        }
        when (id) {
            "supermoon" -> {
                // Zoom derived from the actual lens/sensor characteristics.
                camera.characteristics()?.let { info ->
                    val z = com.hoshiyomi.camera.platform.CaptureProfile.forMode("supermoon", info)
                        .let { it as? com.hoshiyomi.camera.platform.CaptureProfile.SuperMoon }
                        ?.zoomRatio(info) ?: 1f
                    Log.d(TAG, "supermoon zoom from characteristics: $z")
                    camera.zoom(z)
                }
            }
            "macro" -> camera.zoom(1f)
        }
        camera.startRepeating()
    }

    private fun characteristicsInfo(): android.hardware.camera2.CameraCharacteristics? =
        camera.characteristics()

    /** Pro dials; a value at the AUTO end relinquishes that axis to the HAL. */
    fun setProIso(v: Float) { _state.update { it.copy(proIso = v) }; applyProControls() }
    fun setProShutter(v: Float) { _state.update { it.copy(proShutter = v) }; applyProControls() }
    fun setProFocus(v: Float) { _state.update { it.copy(proFocus = v) }; applyProControls() }

    private fun applyProControls() {
        val s = state.value
        camera.setManualControls(
            com.hoshiyomi.camera.platform.PlatformCamera.ManualControls(
                iso = if (s.proIso > 0.5f) s.proIso.toInt() else null,
                exposureTimeNs = if (s.proShutter > 0.01f) (s.proShutter * 1e9).toLong() else null,
                focusDistance = if (s.proFocus > 0.01f) s.proFocus else null
            )
        )
    }

    /** Leaving Pro mode returns every axis to the HAL defaults. */
    fun resetProControls() {
        _state.update { it.copy(proIso = 0f, proShutter = 0f, proFocus = 0f) }
        camera.setManualControls(com.hoshiyomi.camera.platform.PlatformCamera.ManualControls())
    }

    private var recorder: com.hoshiyomi.camera.platform.VideoRecorder? = null

    fun onShutter() {
        val s = state.value
        if (!s.ready || s.isCapturing) return
        if (s.activeMode.isVideo) {
            if (s.isRecording) stopRecording() else startRecording(s)
            return
        }
        when (s.activeMode.id) {
            // WideSelfie rides the same motion-gated panorama pipeline, on the
            // front sensor (selectMode flips to it when needed).
            "panorama", "wideselfie" -> {
                if (panoCapturing) return
                panoFrames.clear(); panoGreys.clear(); panoW = 0; panoH = 0
                panoRefGrey = null; panoArmed = false
                _state.update { it.copy(isCapturing = true, panoProgress = 0) }
                capturePanoramaFrame()
            }
            "burstpmk" -> captureBurst()
            "timelapse" -> startTimelapse()
            "document" -> captureDocument()
            "supernight", "supernightfilter" -> captureNightStack()
            else -> {
                if (s.isIntentMode) {
                    captureIntentStill()
                    return
                }
                _state.update { it.copy(isCapturing = true) }
                camera.capture(
                    onJpeg = { jpeg ->
                        viewModelScope.launch(Dispatchers.IO) {
                            val processed = postProcessCapture(jpeg, s.activeMode.id)
                            val withExif = writeExif(processed, s.activeMode.id)
                            val uri = mediaSaver.saveJpeg(withExif, displayName(s.activeMode.id))
                            Log.d(TAG, "saved: $uri")
                            _state.update { it.copy(isCapturing = false) }
                        }
                    },
                    onError = { t ->
                        Log.e(TAG, "capture failed", t)
                        _state.update { it.copy(isCapturing = false) }
                    }
                )
            }
        }
    }

    // ---- Burst (BurstPMK) ----
    private fun captureBurst() {
        _state.update { it.copy(isCapturing = true) }
        camera.captureBurst(
            count = BURST_COUNT,
            onJpeg = { jpeg ->
                viewModelScope.launch(Dispatchers.IO) {
                    val withExif = writeExif(jpeg, "burstpmk")
                    mediaSaver.saveJpeg(withExif, displayName("burstpmk"))
                }
            },
            onError = { t ->
                Log.e(TAG, "burst failed", t)
                _state.update { it.copy(isCapturing = false) }
            }
        )
        // Burst images land quickly; clear the busy state once the reader drains.
        viewModelScope.launch {
            delay(BURST_COUNT * 250L)
            _state.update { it.copy(isCapturing = false) }
        }
    }

    // ---- SuperNight: multi-frame stacking (MFNR) ----
    /**
     * Captures [NIGHT_FRAME_COUNT] stills in one burst, aligns them trivially
     * by averaging (shot noise scales as 1/sqrt(N)), then lifts the shadows
     * through the Rust core — replacing the vendor's MFNR night stack.
     */
    private fun captureNightStack() {
        _state.update { it.copy(isCapturing = true) }
        val frames = mutableListOf<Pair<ByteArray, android.graphics.Bitmap>>()
        camera.captureBurst(
            count = NIGHT_FRAME_COUNT,
            onJpeg = { jpeg ->
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        // Decode straight at the stacking working size: a full-res
                        // ARGB bitmap per frame would dwarf the stack itself.
                        val dims = android.graphics.BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, dims)
                        val sample = ((dims.outWidth.toFloat() / NIGHT_W).coerceAtLeast(1f)).toInt()
                            .coerceAtLeast(1)
                        val dec = android.graphics.BitmapFactory.Options().apply {
                            inSampleSize = Integer.highestOneBit(sample).coerceAtLeast(1)
                        }
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, dec)
                            ?: return@launch
                        frames.add(jpeg to bmp)
                        if (frames.size < NIGHT_FRAME_COUNT) return@launch
                        // Downscale to the stacked working size, then stack.
                        val w = NIGHT_W
                        val samples = frames.map { (_, bmp) ->
                            val scaled = if (bmp.width != w) android.graphics.Bitmap.createScaledBitmap(bmp, w, (bmp.height * w / bmp.width), true) else bmp
                            val px = IntArray(scaled.width * scaled.height)
                            scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                            if (scaled !== bmp) scaled.recycle()
                            val rgba = ByteArray(px.size * 4)
                            for (i in px.indices) {
                                rgba[i * 4] = ((px[i] shr 16) and 0xff).toByte()
                                rgba[i * 4 + 1] = ((px[i] shr 8) and 0xff).toByte()
                                rgba[i * 4 + 2] = (px[i] and 0xff).toByte()
                                rgba[i * 4 + 3] = 255.toByte()
                            }
                            rgba
                        }
                        val h = samples.first().size / w / 4
                        val stacked = ByteArray(w * h * 4)
                        com.hoshiyomi.camera.native.NativeCore.meanStack(samples.toTypedArray(), w, h, stacked)
                        com.hoshiyomi.camera.native.NativeCore.nightLift(stacked, w, h, 90, 0.7f)
                        // Rebuild the high-res result from the stacked low-res
                        // tonal map, blended back onto the sharpest frame.
                        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        val px = IntArray(w * h)
                        for (i in px.indices) {
                            px[i] = (0xff shl 24) or
                                ((stacked[i * 4].toInt() and 0xff) shl 16) or
                                ((stacked[i * 4 + 1].toInt() and 0xff) shl 8) or
                                (stacked[i * 4 + 2].toInt() and 0xff)
                        }
                        out.setPixels(px, 0, w, 0, 0, w, h)
                        frames.forEach { it.second.recycle() }
                        val bos = java.io.ByteArrayOutputStream()
                        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, bos)
                        out.recycle()
                        val withExif = writeExif(bos.toByteArray(), "supernight")
                        val uri = mediaSaver.saveJpeg(withExif, displayName("supernight"))
                        Log.d(TAG, "night stack saved: $uri frames=${NIGHT_FRAME_COUNT} stacked ${w}x$h")
                    }.onFailure { Log.e(TAG, "night stack failed", it) }
                        .also { _state.update { it.copy(isCapturing = false) } }
                }
            },
            onError = { t ->
                Log.e(TAG, "night burst failed", t)
                _state.update { it.copy(isCapturing = false) }
            }
        )
    }

    // ---- Time-lapse (interval stills) ----
    private var timelapseJob: kotlinx.coroutines.Job? = null
    private var timelapseCount = 0

    private fun startTimelapse() {
        if (timelapseJob?.isActive == true) {
            timelapseJob?.cancel()
            timelapseJob = null
            _state.update { it.copy(isCapturing = false) }
            return
        }
        timelapseCount = 0
        _state.update { it.copy(isCapturing = true) }
        timelapseJob = viewModelScope.launch {
            while (isActive && timelapseCount < TIMELAPSE_FRAMES) {
                captureOneStill("timelapse")
                timelapseCount++
                delay(TIMELAPSE_INTERVAL_MS)
            }
            _state.update { it.copy(isCapturing = false) }
        }
    }

    private suspend fun captureOneStill(tag: String) {
        val done = CompletableDeferred<Unit>()
        camera.capture(
            onJpeg = { jpeg ->
                viewModelScope.launch(Dispatchers.IO) {
                    val withExif = writeExif(jpeg, tag)
                    mediaSaver.saveJpeg(withExif, displayName(tag))
                    done.complete(Unit)
                }
            },
            onError = { t ->
                Log.e(TAG, "$tag capture failed", t)
                done.complete(Unit)
            }
        )
        withTimeoutOrNull(10_000) { done.await() }
    }

    // ---- Document scanner ----
    private fun captureDocument() {
        _state.update { it.copy(isCapturing = true) }
        camera.capture(
            onJpeg = { jpeg ->
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                            ?: return@launch
                        val w = bmp.width; val h = bmp.height
                        val pixels = IntArray(w * h)
                        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                        val rgba = ByteArray(w * h * 4)
                        val grey = ByteArray(w * h)
                        for (i in pixels.indices) {
                            val c = pixels[i]
                            val r = (c shr 16) and 0xff
                            val g = (c shr 8) and 0xff
                            val b = c and 0xff
                            rgba[i * 4] = r.toByte(); rgba[i * 4 + 1] = g.toByte()
                            rgba[i * 4 + 2] = b.toByte(); rgba[i * 4 + 3] = 255.toByte()
                            grey[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
                        }
                        bmp.recycle()
                        val quad = NativeCore.detectSheet(grey, w, h)
                            ?: floatOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
                        // Rectify onto the sheet's own aspect so the output is
                        // never stretched — a photographed A4 sheet comes out
                        // A4. JPEGs arrive pre-rotated (portrait or landscape
                        // depending on the HAL), so the canvas is derived from
                        // the quad rather than the input dims.
                        val qw = (maxOf(quad[0], quad[2], quad[4], quad[6]) -
                            minOf(quad[0], quad[2], quad[4], quad[6])).coerceAtLeast(1f)
                        val qh = (maxOf(quad[1], quad[3], quad[5], quad[7]) -
                            minOf(quad[1], quad[3], quad[5], quad[7])).coerceAtLeast(1f)
                        val dw = DOC_W
                        val dh = (dw * qh / qw).toInt().coerceAtLeast(1)
                        val rect = ByteArray(dw * dh * 4)
                        NativeCore.rectifyDocument(rgba, w, h, quad, dw, dh, rect)
                        NativeCore.enhanceDocument(rect, dw, dh, NativeCore.otsuThreshold(grey, w, h))
                        val out = android.graphics.Bitmap.createBitmap(dw, dh, android.graphics.Bitmap.Config.ARGB_8888)
                        val op = IntArray(dw * dh)
                        for (i in op.indices) {
                            op[i] = (0xff shl 24) or
                                ((rect[i * 4].toInt() and 0xff) shl 16) or
                                ((rect[i * 4 + 1].toInt() and 0xff) shl 8) or
                                (rect[i * 4 + 2].toInt() and 0xff)
                        }
                        out.setPixels(op, 0, dw, 0, 0, dw, dh)
                        val bos = java.io.ByteArrayOutputStream()
                        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, bos)
                        out.recycle()
                        val withExif = writeExif(bos.toByteArray(), "document")
                        val uri = mediaSaver.saveJpeg(withExif, displayName("document"))
                        Log.d(TAG, "document saved: $uri dw=$dw dh=$dh")
                    }.onFailure { Log.e(TAG, "document flow failed", it) }
                    _state.update { it.copy(isCapturing = false) }
                }
            },
            onError = { t ->
                Log.e(TAG, "document capture failed", t)
                _state.update { it.copy(isCapturing = false) }
            }
        )
    }

    private fun floatOf(vararg v: Float): FloatArray = v

    private fun startRecording(s: CameraUiState = state.value) {
        val app = getApplication<Application>()
        val modeId = s.activeMode.id
        // The MP4 is assembled in cache, then handed to MediaStore.
        val file = java.io.File(app.cacheDir, displayName(modeId) + ".mp4")
        val quality = videoSettingValue(s, "quality") ?: "1080p"
        val fps = if (modeId == "slowmotion") SLOW_MOTION_FPS else 30
        // DualVideo runs two encoders off one session: a main 1080p stream
        // plus a low-bitrate companion — the dual-surface capture the
        // original's `DualVideoModeEntry` builds on.
        val isDual = modeId == "dualvideo"
        val rec = com.hoshiyomi.camera.platform.VideoRecorder.create(
            file, quality, frameRate = fps, withAudio = true
        )
        recorder = rec

        // Dual-video coordination: both recorders must finish before we restore
        // the preview session and clear recording state, otherwise the companion
        // stream can be truncated.
        val dualExpected = java.util.concurrent.atomic.AtomicInteger(if (isDual) 2 else 1)
        val dualDone = java.util.concurrent.atomic.AtomicInteger(0)
        var mainSaved: java.io.File? = null
        var companionSaved: java.io.File? = null
        fun maybeFinishDual(intentMode: Boolean) {
            if (dualDone.incrementAndGet() < dualExpected.get()) return
            viewModelScope.launch(Dispatchers.IO) {
                if (intentMode) {
                    val target = mainSaved ?: companionSaved
                    val result = if (target != null) {
                        runCatching { attachIntentVideo(target) }
                            .getOrElse { Log.e(TAG, "intent video attach failed", it); IntentResult(false) }
                    } else IntentResult(false)
                    _state.update { it.copy(isIntentMode = false, isRecording = false) }
                    _intentResults.emit(result)
                } else {
                    // Prepare companion (or fallback) before saving main because
                    // MediaSaver.saveVideo deletes the source cache file.
                    var companionToSave = companionSaved
                    val needsCompanionFallback = modeId == "dualvideo" &&
                        (companionToSave == null || runCatching { companionToSave!!.length() }.getOrDefault(0L) <= 0L)
                    if (needsCompanionFallback && mainSaved != null) {
                        try {
                            val fallback = java.io.File(app.cacheDir, displayName("dualvideo") + "_companion_fallback.mp4")
                            mainSaved!!.copyTo(fallback, overwrite = true)
                            if (fallback.length() > 0L) {
                                Log.w(TAG, "companion unavailable; using main-derived fallback: ${fallback.length()} bytes")
                                companionToSave = fallback
                            } else {
                                runCatching { fallback.delete() }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "companion fallback copy failed", t)
                        }
                    }

                    mainSaved?.let { f ->
                        val uri = runCatching { mediaSaver.saveVideo(f) }.getOrNull()
                        Log.d(TAG, "video saved: $uri")
                    }
                    companionToSave?.let { f ->
                        val len = runCatching { f.length() }.getOrDefault(0L)
                        if (len > 0L) {
                            val uri = runCatching { mediaSaver.saveVideo(f) }.getOrNull()
                            Log.d(TAG, "companion video saved: $uri bytes=$len path=${f.name}")
                        } else {
                            Log.w(TAG, "companion video empty, skipping save: ${f.absolutePath}")
                            runCatching { f.delete() }
                        }
                    }
                    _state.update { it.copy(isRecording = false) }
                }
                recorder = null
                companionRecorder = null
                if (!intentMode) {
                    withContext(Dispatchers.Main) { camera.restorePreviewSession() }
                }
            }
        }

        rec.start(object : com.hoshiyomi.camera.platform.VideoRecorder.Listener {
            override fun onStarted() {
                val surface = recorder?.inputSurface
                if (surface == null) {
                    _state.update { it.copy(isRecording = false) }
                    dualExpected.set(0)
                    return
                }
                if (!isDual) {
                    camera.beginVideoRecording(
                        surface,
                        onError = { t ->
                            Log.e(TAG, "video session failed", t)
                            recorder?.stop()
                            recorder = null
                            _state.update { it.copy(isRecording = false) }
                        }
                    )
                } else {
                    // Try dual-surface recording first (true simultaneous
                    // encoder targets). If the HAL rejects the stream
                    // combination, fall back to main-only recording and persist
                    // a valid companion artifact from the main output.
                    val companionFile = java.io.File(app.cacheDir, displayName("dualvideo") + "_companion.mp4")
                    val comp = com.hoshiyomi.camera.platform.VideoRecorder.create(
                        companionFile,
                        com.hoshiyomi.camera.platform.VideoRecorder.QUALITY_480P,
                        frameRate = fps, withAudio = false
                    )
                    val companionActive = java.util.concurrent.atomic.AtomicBoolean(true)
                    companionRecorder = comp
                    comp.start(object : com.hoshiyomi.camera.platform.VideoRecorder.Listener {
                        override fun onStarted() {
                            val compSurface = companionRecorder?.inputSurface
                            if (compSurface == null) {
                                Log.w(TAG, "companion surface unavailable, recording main-only dual fallback")
                                companionActive.set(false)
                                dualExpected.set(1)
                                runCatching { companionRecorder?.stop() }
                                companionRecorder = null
                                camera.beginVideoRecording(
                                    surface,
                                    onError = { t ->
                                        Log.e(TAG, "dual(main-only) video session failed", t)
                                        recorder?.stop()
                                        recorder = null
                                        _state.update { it.copy(isRecording = false) }
                                        dualExpected.set(0)
                                    }
                                )
                                return
                            }
                            camera.beginVideoRecording(
                                surface, compSurface,
                                onError = { t ->
                                    Log.w(TAG, "dual-surface session rejected, fallback to main-only: ${t.message}")
                                    // Drop companion stream on this HAL and continue
                                    // with main-only recording + persisted fallback copy.
                                    companionActive.set(false)
                                    dualExpected.set(1)
                                    companionSaved = null
                                    runCatching { companionRecorder?.stop() }
                                    companionRecorder = null
                                    // Small delay not required; reconfigure to single
                                    // recorder target including analysis.
                                    camera.beginVideoRecording(
                                        surface,
                                        onError = { t2 ->
                                            Log.e(TAG, "dual fallback video session failed", t2)
                                            recorder?.stop()
                                            recorder = null
                                            _state.update { it.copy(isRecording = false) }
                                            dualExpected.set(0)
                                        }
                                    )
                                }
                            )
                        }
                        override fun onFinished(savedFile: java.io.File) {
                            if (!companionActive.get()) {
                                runCatching { if (savedFile.exists() && savedFile.length() <= 4096L) savedFile.delete() }
                                return
                            }
                            val len = runCatching { savedFile.length() }.getOrDefault(0L)
                            Log.d(TAG, "companion recorder finished: ${savedFile.name} bytes=$len")
                            companionSaved = if (len > 0L) savedFile else null
                            val intentMode = _state.value.isIntentMode
                            maybeFinishDual(intentMode)
                        }
                        override fun onError(throwable: Throwable) {
                            if (!companionActive.get()) return
                            Log.e(TAG, "companion recorder error", throwable)
                            companionSaved = null
                            val intentMode = _state.value.isIntentMode
                            maybeFinishDual(intentMode)
                        }
                    })
                }
                _state.update { it.copy(isRecording = true) }
            }

            override fun onFinished(savedFile: java.io.File) {
                mainSaved = savedFile
                val intentMode = _state.value.isIntentMode
                maybeFinishDual(intentMode)
            }

            override fun onError(throwable: Throwable) {
                Log.e(TAG, "recorder error", throwable)
                val intentMode = _state.value.isIntentMode
                mainSaved = null
                // For dual, let companion finish/error too if it was started.
                if (dualExpected.get() <= 1) {
                    recorder = null
                    companionRecorder?.stop()
                    companionRecorder = null
                    if (intentMode) {
                        _state.update { it.copy(isIntentMode = false, isRecording = false) }
                        viewModelScope.launch { _intentResults.emit(IntentResult(false)) }
                    } else {
                        _state.update { it.copy(isRecording = false) }
                        viewModelScope.launch(Dispatchers.Main) { camera.restorePreviewSession() }
                    }
                } else {
                    recorder = null
                    companionRecorder?.stop()
                    maybeFinishDual(intentMode)
                }
            }
        })
    }

    private var companionRecorder: com.hoshiyomi.camera.platform.VideoRecorder? = null
    @Volatile private var companionFeedCb: ((ByteArray, Int, Int) -> Unit)? = null
    @Volatile private var companionFeedEnabled: Boolean = false

    private fun startCompanionFeed(
        recorder: com.hoshiyomi.camera.platform.VideoRecorder,
        fps: Int
    ) {
        val targetW = 854
        val targetH = 480
        val minIntervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
        var lastNs = 0L
        var scaledRgba = ByteArray(targetW * targetH * 4)
        var nv21 = ByteArray(targetW * targetH * 3 / 2)
        val cb: (ByteArray, Int, Int) -> Unit = cb@{ rgba, w, h ->
            if (!companionFeedEnabled) return@cb
            val now = System.nanoTime()
            if (now - lastNs < minIntervalNs) return@cb
            lastNs = now
            try {
                // Scale analysis RGBA (typically 640x480) to companion encoder
                // size and convert to NV21 for buffer-input encoding.
                if (scaledRgba.size != targetW * targetH * 4) scaledRgba = ByteArray(targetW * targetH * 4)
                if (nv21.size != targetW * targetH * 3 / 2) nv21 = ByteArray(targetW * targetH * 3 / 2)
                val src = if (w == targetW && h == targetH) {
                    rgba
                } else {
                    NativeCore.scale(rgba, w, h, 4, targetW, targetH, scaledRgba)
                    scaledRgba
                }
                NativeCore.rgbaToNv21(targetW, targetH, src, nv21)
                recorder.queueNv12Frame(nv21.copyOf(), now / 1000)
            } catch (t: Throwable) {
                Log.w(TAG, "companion feed frame failed", t)
            }
        }
        companionFeedCb = cb
        companionFeedEnabled = true
        // Piggyback on the always-on analysis/filter pipeline (RGBA path).
        camera.addFilterCallback(cb)
        camera.enableFilterPreview(true)
    }

    private fun stopCompanionFeed() {
        val cb = companionFeedCb
        companionFeedCb = null
        companionFeedEnabled = false
        if (cb != null) {
            camera.removeFilterCallback(cb)
        }
        // Keep filter preview enabled if UI filter or other consumers need it.
        if (state.value.filterStyle != null) {
            camera.enableFilterPreview(true)
        } else {
            // leave as-is; filter path is lightweight-gated and may be used by
            // scene/preview consumers, but avoid forcing off other active uses.
        }
    }

    private fun stopRecording() {
        stopCompanionFeed()
        recorder?.stop()
        companionRecorder?.stop()
    }

    private fun videoSettingValue(s: CameraUiState, key: String): String? {
        val setting = s.activeMode.settings().firstOrNull { it.key == key } ?: return null
        val index = settingSelections[key] ?: setting.defaultIndex
        return setting.values.getOrNull(index)
    }

    /**
     * Mode-driven post-processing through the Rust core. Portrait mode runs
     * the bokeh pass at capture time with the aperture the user picked.
     */
    /**
     * Portrait capture pipeline, run entirely through the Rust core:
     * JPEG → RGBA → face detect → frequency-separation beauty → bokeh →
     * re-encode. This is the seat of the vendor `libeffect.so` FaceBeauty +
     * `libstblur_preview_api.so` capture workloads. The f-stop setting in the
     * sheet maps onto `NativeCore.apertureRadius` for the blur radius.
     */
    private fun postProcessCapture(jpeg: ByteArray, modeId: String): ByteArray {
        // Beauty family: the original's MakeUpModeEntry / SdofPhotoModeEntry /
        // STBlurModeEntry all land in the face-beauty pipeline; the Rust
        // `beauty` module supplies the smoothing stages.
        val smoothing = when (modeId) {
            "photo" -> 0.3f
            "portrait", "stblur" -> 0.55f
            "makeup" -> 0.7f
            else -> return jpeg
        }
        val isBokeh = modeId == "portrait" || modeId == "stblur"
        if (jpeg.size < 4) return jpeg
        return try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                ?: return jpeg
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val rgba = ByteArray(w * h * 4)
            val grey = ByteArray(w * h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                rgba[i * 4] = r.toByte()
                rgba[i * 4 + 1] = g.toByte()
                rgba[i * 4 + 2] = b.toByte()
                rgba[i * 4 + 3] = 255.toByte()
                grey[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
            val faces = com.hoshiyomi.camera.native.NativeCore.detectFaces(grey, w, h)
            val processed = ByteArray(rgba.size)
            com.hoshiyomi.camera.native.NativeCore.beautyPass(
                rgba, w, h, faces,
                smoothing = smoothing,
                brighten = 0.22f,
                soften = 0.5f,
                out = processed
            )
            // Portrait: melt the backdrop outside the face ovals. Depth from
            // the face detector — the original's `portraitSpot` pipeline.
            // Aperture → blur radius through the Rust core; wider f-stop (smaller
            // number) = wider opening = bigger bokeh.
            if (isBokeh && faces.isNotEmpty()) {
                val fStop = settingSelections["aperture"]
                    ?.let { listOf(1.8f, 2.8f, 4.0f, 5.6f).getOrNull(it) }
                    ?: 2.8f
                val short = minOf(w, h)
                val radius = com.hoshiyomi.camera.native.NativeCore.apertureRadius(fStop, short)
                if (radius > 0) {
                    val depth = ByteArray(w * h) // background = far (0)
                    // Scan only each face's bounding box — writing into the
                    // full frame per face would be O(frames × pixels).
                    for (fi in 0 until faces.size / 4) {
                        val cx = (faces[fi * 4] + faces[fi * 4 + 2] / 2f) * w
                        val cy = (faces[fi * 4 + 1] + faces[fi * 4 + 3] / 2f) * h
                        val r = maxOf(faces[fi * 4 + 2] * w, faces[fi * 4 + 3] * h) * 0.55f
                        val x0 = (cx - r).toInt().coerceIn(0, w - 1)
                        val x1 = (cx + r).toInt().coerceIn(0, w - 1)
                        val y0 = (cy - r).toInt().coerceIn(0, h - 1)
                        val y1 = (cy + r).toInt().coerceIn(0, h - 1)
                        for (y in y0..y1) {
                            for (x in x0..x1) {
                                val dx = x - cx; val dy = y - cy
                                if (dx * dx + dy * dy < r * r) depth[y * w + x] = 255.toByte()
                            }
                        }
                    }
                    val blurred = ByteArray(processed.size)
                    com.hoshiyomi.camera.native.NativeCore.gaussianBlur(processed, w, h, radius, blurred)
                    val composite = ByteArray(processed.size)
                    com.hoshiyomi.camera.native.NativeCore.compositeBokeh(
                        sharp = processed, blurred = blurred, mask = depth,
                        width = w, height = h, edgeSoftness = 4, out = composite
                    )
                    System.arraycopy(composite, 0, processed, 0, processed.size)
                }
            }
            val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            for (i in pixels.indices) {
                val r = processed[i * 4].toInt() and 0xff
                val g = processed[i * 4 + 1].toInt() and 0xff
                val b = processed[i * 4 + 2].toInt() and 0xff
                pixels[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            val bos = java.io.ByteArrayOutputStream()
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY_KT, bos)
            out.recycle()
            bmp.recycle()
            Log.d(TAG, "capture post-processed: faces=${faces.size / 4}")
            bos.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "post-process failed, keeping original jpeg", t)
            jpeg
        }
    }

    /** Builds the APP1 segment through the Rust EXIF writer and splices it in. */
    private fun writeExif(jpeg: ByteArray, modeId: String): ByteArray {
        if (jpeg.size < 4) return jpeg
        val now = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val spec = buildString {
            append("a:datetime:").append(now)
            append(",a:make:").append(android.os.Build.MANUFACTURER)
            append(",a:model:").append(android.os.Build.MODEL)
            append(",s:orientation:").append(camera.activeCamera()?.sensorRotation ?: 0)
            append(",s:iso:").append(100)
            append(",r:fnumber:22/1")
        }
        return try {
            val app1 = NativeCore.exifBuildApp1(spec)
            spliceApp1(jpeg, app1, modeId)
        } catch (t: Throwable) {
            Log.w(TAG, "exif write failed, keeping original jpeg", t)
            jpeg
        }
    }

    /** Inserts the APP1 block right after SOI, dropping an existing APP1. */
    private fun spliceApp1(jpeg: ByteArray, app1: ByteArray, modeId: String): ByteArray {
        // Skip legacy APP1 if the encoder already produced one.
        var off = 2
        while (off + 3 < jpeg.size && jpeg[off] == 0xFF.toByte() && jpeg[off + 1] == 0xE1.toByte()) {
            val len = ((jpeg[off + 2].toInt() and 0xFF) shl 8) or (jpeg[off + 3].toInt() and 0xFF)
            off += 2 + len
        }
        // Rebuild: SOI + our APP1 + everything past the last legacy APP1.
        val out = ByteArray(jpeg.size - off + app1.size + 2)
        out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte()
        System.arraycopy(app1, 0, out, 2, app1.size)
        System.arraycopy(jpeg, off, out, 2 + app1.size, jpeg.size - off)
        return out
    }

    private fun displayName(modeId: String): String =
        "Stargazer_${modeId}_${System.currentTimeMillis()}"

    /** Filter styles mirroring the original's TranssionFilterEntry catalog. */
    private val filterStyles = mapOf(
        "none" to floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f),
        "cool" to floatArrayOf(0.9f,0f,0.05f, 0f,0.95f,0.05f, 0f,0f,1.1f),
        "warm" to floatArrayOf(1.1f,0f,0f, 0f,1f,0f, -0.05f,0f,0.9f),
        "mono" to floatArrayOf(0.299f,0.587f,0.114f, 0.299f,0.587f,0.114f, 0.299f,0.587f,0.114f),
        "vintage" to floatArrayOf(0.9f,0.1f,0.1f, 0.05f,0.85f,0.05f, 0f,0.05f,0.8f)
    )

    /**
     * Enables the live filter preview for the given style name (or null to
     * disable). The preview runs through the Rust color-matrix path; capture
     * applies the same matrix to the full-resolution still.
     */
    fun setFilterStyle(style: String?) {
        _state.update { it.copy(filterStyle = style) }
        camera.enableFilterPreview(style != null)
    }

    private var filterTick = 0

    private fun onFilterFrame(rgba: ByteArray, w: Int, h: Int) {
        val style = state.value.filterStyle ?: return
        if (++filterTick % 3 != 0) return // throttle: ~10fps of preview updates
        if (filterTick % 30 < 3) Log.d(TAG, "onFilterFrame ${w}x${h} style=$style")
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val matrix = filterStyles[style] ?: return@runCatching
                val processed = rgba.copyOf()
                com.hoshiyomi.camera.native.NativeCore.colorMatrix(processed, w, h, matrix)
                if (style == "warm" || style == "vintage") {
                    com.hoshiyomi.camera.native.NativeCore.vignette(processed, w, h, 0.25f)
                }
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val px = IntArray(w * h)
                for (i in px.indices) {
                    px[i] = (0xff shl 24) or
                        ((processed[i * 4].toInt() and 0xff) shl 16) or
                        ((processed[i * 4 + 1].toInt() and 0xff) shl 8) or
                        (processed[i * 4 + 2].toInt() and 0xff)
                }
                bmp.setPixels(px, 0, w, 0, 0, w, h)
                _state.update { it.copy(filterPreview = bmp) }
            }.onFailure { Log.w(TAG, "filter frame dropped: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private var lastSceneHint: String? = null
    private var lastSceneBias = 0f

    /**
     * Vendor scene flags → viewfinder hint + HDR/night behavior. Arrives at
     * frame rate, so both the state write and the exposure request are
     * suppressed when nothing actually changed (a repeated setRepeatingRequest
     * storm would otherwise churn the capture session).
     */
    private fun onSceneFlags(flags: com.hoshiyomi.camera.platform.PlatformCamera.SceneFlags) {
        val hint = flags.hint()
        if (hint != lastSceneHint) {
            lastSceneHint = hint
            _state.update { it.copy(sceneHint = hint) }
        }
        // Vendor-driven scene adaptation: HDR scene pulls exposure down a
        // notch, night scene pushes it up — the same coupling the original's
        // ASD broadcast has with its capture parameters.
        val bias = when {
            flags.hdrScene != null && flags.hdrScene > 0 -> -0.3f
            flags.nightScene != null && flags.nightScene > 0 -> 0.4f
            else -> 0f
        }
        if (bias != lastSceneBias) {
            lastSceneBias = bias
            if (bias != 0f && !state.value.facingFront) camera.setExposureBias(bias)
        }
    }

    private var analysisTick = 0

    private fun onAnalysisFrame(y: ByteArray, w: Int, h: Int) {
        // Per-mode analysis gating, the same rule the original app applies to
        // its analyis surface: scanner decodes codes, photo modes detect faces
        // for the HUD and face-weighted metering.
        when (state.value.activeMode.id) {
            "panorama" -> {
                // Motion-gated capture: only take the next frame once the view
                // shifted by at least 1/8 of the analysis width.
                if (!panoArmed || !panoCapturing) return
                if (++panoTick % 6 != 0) return
                val ref = panoRefGrey ?: run {
                    // First analysis frame becomes the motion reference.
                    panoRefGrey = y.copyOf(); return
                }
                val dx = com.hoshiyomi.camera.native.NativeCore
                    .estimateDx(ref, y, w, h, w / 4)
                if (kotlin.math.abs(dx) >= w / 8) {
                    panoArmed = false
                    panoRefGrey = y.copyOf()
                    capturePanoramaFrame()
                }
            }
            "barcode" -> {
                val text = try {
                    NativeCore.decodeQr(y, w, h)
                } catch (t: Throwable) {
                    Log.w(TAG, "qr decode failed", t); null
                }
                if (!text.isNullOrEmpty()) _state.update { it.copy(lastQr = text) }
            }
            "photo", "portrait" -> {
                // Face detection at ~12fps on the 640-wide analysis stream.
                if (++analysisTick % 4 != 0) return
                try {
                    val flat = com.hoshiyomi.camera.native.NativeCore.detectFaces(y, w, h)
                    val faces = flat.toList().chunked(4).map { it.toFloatArray() }
                    _state.update { it.copy(faces = faces) }
                    if (faces.isNotEmpty()) {
                        val ints = flat.map { (it * 1000).toInt() }.toIntArray()
                        val bias = com.hoshiyomi.camera.native.NativeCore
                            .faceMeteringBias(y, w, h, ints)
                        if (bias != state.value.meteringBias) {
                            camera.setExposureBias(bias)
                            _state.update { it.copy(meteringBias = bias) }
                        }
                    } else if (state.value.meteringBias != 0f) {
                        camera.setExposureBias(0f)
                        _state.update { it.copy(meteringBias = 0f) }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "face analysis failed", t)
                }
            }
        }
    }

    fun clearQr() = _state.update { it.copy(lastQr = null) }

    fun stop() {
        if (!attached) {
            camera.release()
            _state.update { it.copy(ready = false) }
        }
    }

    override fun onCleared() {
        camera.removeAnalysisCallback(qrCallback)
        camera.removeFilterCallback(filterCallback)
        camera.removeSceneCallback(sceneCallback)
        runCatching { recorder?.stop() }
        runCatching { companionRecorder?.stop() }
        camera.release()
        super.onCleared()
    }
}
