package com.hoshiyomi.camera.platform

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest

/**
 * Capture-parameter presets — the Stargazer counterpart of the reference camera app's per-mode
 * `captureParams` (MacroModeEntry / HighDefinitionModeEntry / SuperNightEntry /
 * SuperMoonRestriction / MagicSkyEntry / ASDModeEntry / SdofPhotoEntry). Each
 * profile maps mode intent onto standard Camera2 controls plus the probed
 * vendor extension keys from [VendorKeys]; keys the HAL lacks stay no-ops, so
 * every mode degrades gracefully on unsupported silicon.
 */
sealed class CaptureProfile {

    /** Apply to the repeating preview request. */
    open fun applyPreview(builder: CaptureRequest.Builder, info: CameraCharacteristics) {}

    /** Apply to a still-capture request (defaults to the preview settings). */
    open fun applyCapture(builder: CaptureRequest.Builder, info: CameraCharacteristics) {
        applyPreview(builder, info)
    }

    /** Human-facing summary for the docs/settings surface. */
    abstract val label: String

    /** Plain photo — AF continuous, auto 3A, no extensions. */
    data object Photo : CaptureProfile() {
        override val label = "Photo"
    }

    /** Close focus: AF_MACRO when the lens supports it, else pull to the
     *  minimum focus distance, plus a low ISO to keep macro texture noise-free. */
    class Macro(private val minFocusDistance: Float?) : CaptureProfile() {
        override val label = "Macro"
        override fun applyPreview(builder: CaptureRequest.Builder, info: CameraCharacteristics) {
            val hasMacro = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CameraMetadata.CONTROL_AF_MODE_MACRO) == true
            if (hasMacro) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                minFocusDistance?.takeIf { it > 0 }?.let { d ->
                    // Closest focus the lens allows.
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, d * 0.9f)
                }
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
    }

    /** Maximum JPEG resolution + lowest compression loss + edge enhancement. */
    class HighDefinition : CaptureProfile() {
        override val label = "High Definition"
        override fun applyCapture(builder: CaptureRequest.Builder, info: CameraCharacteristics) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            // Vendor HD knob (com.transsion.hdMode etc.); no-op without the key.
            VendorKeys.Tran.asdMode.apply(builder, 1)
        }
    }

    /** Super Night: AE bias up, long exposure bias, vendor night mode. */
    class SuperNight(@androidx.annotation.IntRange(0, 255) private val strength: Int = 1) : CaptureProfile() {
        override val label = "Super Night"
        override fun applyPreview(builder: CaptureRequest.Builder, info: CameraCharacteristics) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            // Bias the AE toward brighter captures without losing auto metering.
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 2)
            VendorKeys.Tran.nightMode.apply(builder, strength)
        }
        override fun applyCapture(builder: CaptureRequest.Builder, info: CameraCharacteristics) {
            applyPreview(builder, info)
            VendorKeys.Mtk.mfnrMfb.apply(builder, 1) // multi-frame noise reduction
        }
    }

    /** Super Night + tone curve — the SuperNightFilterEntry role. */
    class SuperNightFilter : CaptureProfile() {
        override val label = "Super Night Filter"
        override fun applyPreview(builder: CaptureRequest.Builder, info: CameraCharacteristics) {
            SuperNight().applyPreview(builder, info)
            VendorKeys.Tran.filterId.apply(builder, 1)
        }
        override fun applyCapture(builder: CaptureRequest.Builder, info: CameraCharacteristics) {
            SuperNight().applyCapture(builder, info)
            VendorKeys.Tran.filterId.apply(builder, 1)
        }
    }

    /** Super Moon: longest focal length the lens rig supports, exposure pulled
     *  down so the disc stays bright without blowing out. The zoom comes from
     *  the sensor characteristics — optical max first, then any digital head
     *  room — never a hardcoded constant. */
    class SuperMoon : CaptureProfile() {
        override val label = "Super Moon"
        override fun applyCapture(builder: CaptureRequest.Builder, info: CameraCharacteristics) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -3)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, 100)
            VendorKeys.Tran.magicSkyMode.apply(builder, 0) // neutral scene flags
        }
        /** Zoom ratio: optical max, plus half of the digital range. */
        fun zoomRatio(info: CameraCharacteristics): Float {
            val zoom = info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val optical = info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.maxOrNull() ?: 0f
            // Prefer an optical reach ~6x when present; otherwise the HAL's max.
            val opticalZoom = if (optical > 0f) optical / 4.5f else zoom?.upper ?: 1f
            val max = (opticalZoom * 1.0f) + (zoom?.let { (it.upper - it.lower) * 0.3f } ?: 0f)
            return max.coerceIn(1f, zoom?.upper ?: max.coerceAtLeast(1f))
        }
    }

    /** Magic Sky: vendor magic-sky scene + saturation boost. */
    class MagicSky : CaptureProfile() {
        override val label = "Magic Sky"
        override fun applyPreview(builder: CaptureRequest.Builder, info: CameraCharacteristics) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            VendorKeys.Tran.magicSkyMode.apply(builder, 1)
        }
        override fun applyCapture(builder: CaptureRequest.Builder, info: CameraCharacteristics) {
            applyPreview(builder, info)
        }
    }

    /** Auto scene detection: vendor ASD drives our HDR/mode switching. */
    class ASD : CaptureProfile() {
        override val label = "Auto Scene"
        override fun applyPreview(builder: CaptureRequest.Builder, info: CameraCharacteristics) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            VendorKeys.Tran.asdMode.apply(builder, 1)
        }
    }

    /** Slow motion: request the highest-speed AE fps range the sensor exposes. */
    class SlowMotion : CaptureProfile() {
        override val label = "Slow Motion"
        override fun applyPreview(builder: CaptureRequest.Builder, info: CameraCharacteristics) {
            // Pick the largest upper bound reachable; 120/240 only on capable sensors.
            val ranges = info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val best = ranges?.maxByOrNull { it.upper }
            best?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }
    }

    companion object {
        /** Map a Stargazer mode id onto its capture profile. */
        fun forMode(id: String, info: CameraCharacteristics?): CaptureProfile = when (id) {
            "macro" -> Macro(info?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f)
            "highdefinition" -> HighDefinition()
            "supernight" -> SuperNight()
            "supernightfilter" -> SuperNightFilter()
            "supermoon" -> SuperMoon()
            "magicsky" -> MagicSky()
            "asd" -> ASD()
            "slowmotion" -> SlowMotion()
            else -> Photo
        }
    }
}
