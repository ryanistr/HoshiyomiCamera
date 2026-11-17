package com.hoshiyomi.camera.mode

/**
 * A camera mode — the unit of feature composition in the Stargazer camera,
 * mirroring the reference camera app's `ICameraMode` + `ModeFeatureEntryBase` split.
 *
 * Modes are enumerated by [id], ordered per camera direction in
 * [ModeRegistry], and render their own overlay through [overlay]; the capture
 * request itself is composed from [captureParams].
 */
interface CameraMode {
    /** Unique mode id — lifecycle-stable, used for persistence. */
    val id: String

    /** Display name resource-free title. */
    val title: String

    /** Whether this mode records video (changes the shutter into a REC button). */
    val isVideo: Boolean

    /** Settings the mode shows in the settings sheet. */
    fun settings(): List<ModeSetting> = emptyList()

    /** Substance overlay composables drawn above the preview. */
    fun overlay(): Nothing? = null
}

/** A toggle/choice exposed by a mode. */
data class ModeSetting(
    val key: String,
    val label: String,
    val values: List<String>,
    val defaultIndex: Int = 0
)

/** Mode registry — the Stargazer counterpart of the reference camera app's resource string-arrays
 * `build_in_features_back_mode_order` / `build_in_features_front_mode_order`. */
object ModeRegistry {

    val photo = object : CameraMode {
        override val id = "photo"
        override val title = "Photo"
        override val isVideo = false
        override fun settings() = listOf(
            ModeSetting("flash", "Flash", listOf("Auto", "On", "Off")),
            ModeSetting("grid", "Grid lines", listOf("Off", "On"))
        )
    }

    val video = object : CameraMode {
        override val id = "video"
        override val title = "Video"
        override val isVideo = true
        override fun settings() = listOf(
            ModeSetting("quality", "Quality", listOf("1080p", "720p", "4K"))
        )
    }

    val portrait = object : CameraMode {
        override val id = "portrait"
        override val title = "Portrait"
        override val isVideo = false
        override fun settings() = listOf(
            ModeSetting("aperture", "Aperture", listOf("f1.8", "f2.8", "f4.0", "f5.6"))
        )
    }

    /** MakeUp (the reference camera app `MakeUpModeEntry`): the face-beauty family at its
     *  strongest smoothing — the Rust `beauty` module replaces the vendor's
     *  `libeffect.so` FaceBeauty + makeup pipeline. */
    val makeup = object : CameraMode {
        override val id = "makeup"
        override val title = "Makeup"
        override val isVideo = false
        override fun settings() = listOf(
            ModeSetting("smoothing", "Smoothing", listOf("Light", "Normal", "Strong"))
        )
    }

    /** WideSelfie (the reference camera app `WideSelfieModeEntry`): front-camera panorama, gated to
     *  the front sensor like the original's mode list places it. */
    val wideselfie = object : CameraMode {
        override val id = "wideselfie"
        override val title = "Wide Selfie"
        override val isVideo = false
    }

    /** STBlur (the reference camera app `STBlurModeEntry`): front-camera portrait bokeh. */
    val stblur = object : CameraMode {
        override val id = "stblur"
        override val title = "Selfie Blur"
        override val isVideo = false
        override fun settings() = portrait.settings()
    }

    val pro = object : CameraMode {
        override val id = "professional"
        override val title = "Pro"
        override val isVideo = false
        override fun settings() = listOf(
            ModeSetting("iso", "ISO", listOf("Auto", "100", "200", "400", "800", "1600")),
            ModeSetting("wb", "White balance", listOf("Auto", "Daylight", "Cloudy", "Incandescent")),
            // the reference camera app's MeteringMode: auto/center/spot/average (professional scope).
            ModeSetting("metering", "Metering", listOf("Auto", "Center", "Spot", "Average"))
        )
    }

    val barcode = object : CameraMode {
        override val id = "barcode"
        override val title = "Scanner"
        override val isVideo = false
    }

    val panorama = object : CameraMode {
        override val id = "panorama"
        override val title = "Panorama"
        override val isVideo = false
    }

    // ---- capture-profile preset modes (the reference camera app: MacroModeEntry,
    // HighDefinitionModeEntry, SuperNightEntry, SuperNightFilterModeEntry,
    // SuperMoonModeEntry, ASDModeEntry, SdofPhotoEntry) ----
    val macro = object : CameraMode {
        override val id = "macro" ; override val title = "Macro" ; override val isVideo = false
    }
    val highdefinition = object : CameraMode {
        override val id = "highdefinition" ; override val title = "HD" ; override val isVideo = false
    }
    val supernight = object : CameraMode {
        override val id = "supernight" ; override val title = "Super Night" ; override val isVideo = false
    }
    val supernightfilter = object : CameraMode {
        override val id = "supernightfilter" ; override val title = "Night Filter" ; override val isVideo = false
    }
    val supermoon = object : CameraMode {
        override val id = "supermoon" ; override val title = "Super Moon" ; override val isVideo = false
    }
    val asd = object : CameraMode {
        override val id = "asd" ; override val title = "Auto Scene" ; override val isVideo = false
    }

    // ---- video-family modes (the reference camera app: SlowMotionModeEntry, TimeLapseVideoModeEntry,
    // DualVideoModeEntry, MovieModeEntry, FunVideoModeEntry) ----
    val slowmotion = object : CameraMode {
        override val id = "slowmotion" ; override val title = "Slow-Mo" ; override val isVideo = true
    }
    val timelapse = object : CameraMode {
        override val id = "timelapse" ; override val title = "Time-Lapse" ; override val isVideo = false
    }
    // ---- capture-flow modes (the reference camera app: BurstPMKModeEntry, DocumentEntry) ----
    val burstpmk = object : CameraMode {
        override val id = "burstpmk" ; override val title = "Burst" ; override val isVideo = false
    }
    val document = object : CameraMode {
        override val id = "document" ; override val title = "Document" ; override val isVideo = false
    }

    /**
     * Mode order per camera facing, following the original's
     * `build_in_features_back_mode_order`, plus the three modes the original
     * exposes from the same panel that our capture flows implement
     * (Super Night / Super Moon / HD); AR/Avatar modes live in the separate
     * AR subsystem.
     */
    val backModes: List<CameraMode> = listOf(
        video, asd, makeup, portrait, supernight, supernightfilter, supermoon,
        highdefinition, panorama, pro, slowmotion, burstpmk, macro,
        document, timelapse, barcode
    )
    /** Front camera order — the original's list minus the excluded gimmick modes. */
    val frontModes: List<CameraMode> = listOf(
        video, asd, makeup, portrait, stblur, supernight, panorama,
        slowmotion, wideselfie, timelapse, barcode
    )

    fun modesFor(facingFront: Boolean): List<CameraMode> =
        if (facingFront) frontModes else backModes

    fun modeById(id: String): CameraMode? {
        (backModes + frontModes).firstOrNull { it.id == id }?.let { return it }
        // Plain Photo is the registry's implicit default — the original's mode
        // arrays list feature modes only, Photo being the fallback — so it
        // resolves even though it is not a chip.
        return if (id == "photo") photo else null
    }
}
