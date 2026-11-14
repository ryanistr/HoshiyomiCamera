package com.hoshiyomi.camera.platform

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.util.Log

/**
 * Vendor-extension key registry — direct reimplementation of TCAM's
 * `adapter/platformcamera/CaptureRequestKey` + `CaptureResultKey` + `Key`.
 *
 * Camera HAL extensions are constructed from raw key-name strings at runtime
 * and probed once against the device's [CameraCharacteristics]. Keys the HAL
 * does not define are marked [Status.NOT_EXIST] and every subsequent write is
 * a silent no-op, so the same code path runs on any phone: on a device that
 * ships the extension the feature stays enabled, elsewhere it silently
 * degrades. 486 such key names were extracted from the original APK.
 */
object VendorKeys {
    private const val TAG = "VendorKeys"

    /** Tri-state probe result mirroring the original `Key` class. */
    enum class Status { UNKNOWN, EXIST, NOT_EXIST }

    class RequestKey<T>(
        val name: String,
        private val clazz: Class<T>,
        /** Session-level keys can only be written before the session is built. */
        val isSessionKey: Boolean = false
    ) {
        @Volatile var status: Status = Status.UNKNOWN
            private set

        private val key: CaptureRequest.Key<T>? = try {
            CaptureRequest.Key(name, clazz)
        } catch (t: Throwable) {
            Log.d(TAG, "request key construction rejected: $name, $t")
            null
        }

        /** True when the key was constructed and probed as supported. */
        val isExist: Boolean get() = status == Status.EXIST

        fun apply(builder: CaptureRequest.Builder, value: T) {
            if (status == Status.NOT_EXIST) return
            val k = key ?: run { status = Status.NOT_EXIST; return }
            try {
                builder.set(k, value)
                status = Status.EXIST
            } catch (t: Throwable) {
                // Most commonly: "this key is not defined" from the HAL.
                Log.d(TAG, "request key not supported: $name, $t")
                status = Status.NOT_EXIST
            }
        }
    }

    class ResultKey<T>(val name: String, private val clazz: Class<T>) {
        @Volatile var status: Status = Status.UNKNOWN
            private set

        private val key: CaptureResult.Key<T>? = try {
            CaptureResult.Key(name, clazz)
        } catch (t: Throwable) {
            null
        }

        fun read(result: CaptureResult): T? {
            if (status == Status.NOT_EXIST) return null
            val k = key ?: run { status = Status.NOT_EXIST; return null }
            return try {
                result.get(k).also { status = Status.EXIST }
            } catch (t: Throwable) {
                Log.d(TAG, "result key not supported: $name, $t")
                status = Status.NOT_EXIST
                null
            }
        }
    }

    class CharacteristicsKey<T>(val name: String, private val clazz: Class<T>) {
        @Volatile var status: Status = Status.UNKNOWN
            private set

        private val key: CameraCharacteristics.Key<T>? = try {
            CameraCharacteristics.Key(name, clazz)
        } catch (t: Throwable) {
            null
        }

        fun read(info: CameraCharacteristics): T? {
            if (status == Status.NOT_EXIST) return null
            val k = key ?: run { status = Status.NOT_EXIST; return null }
            return try {
                info.get(k).also { if (it != null) status = Status.EXIST else status = Status.NOT_EXIST }
            } catch (t: Throwable) {
                Log.d(TAG, "characteristics key not supported: $name, $t")
                status = Status.NOT_EXIST
                null
            }
        }
    }

    // Known MediaTek HAL extensions (subset of the 53 com.mediatek.* keys).
    object Mtk {
        val hdrMode = RequestKey("com.mediatek.hdrfeature.hdrMode", Int::class.java)
        val zslMode = RequestKey("com.mediatek.control.capture.zsl.mode", Int::class.java)
        val flipMode = RequestKey("com.mediatek.control.capture.flipmode", Int::class.java)
        val mfnrMfb = RequestKey("com.mediatek.mfnrfeature.mfbmode", Int::class.java)
        val videoHfps = RequestKey("com.mediatek.streamingfeature.hfpsMode", Int::class.java)
        val multiCamMode = RequestKey("com.mediatek.multicamfeature.multiCamFeatureMode", Int::class.java)
        // TCAM's MeteringMode setting writes 0=center / 1=spot / 2=average here.
        val aeMeteringMode = RequestKey("com.mediatek.3afeature.aeMeteringMode", ByteArray::class.java)
        val proprietaryRequest = RequestKey(
            "com.mediatek.configure.setting.proprietaryRequest", Int::class.java,
            isSessionKey = true
        )
    }

    // Known Transsion HAL extensions (subset of the 433 com.transsion.* keys).
    object Tran {
        val faceBeautyMode = RequestKey("com.transsion.facebeautymode", Int::class.java)
        val faceBeautyLevel = RequestKey("com.transsion.facebeautylevel", Int::class.java)
        val makeUpMode = RequestKey("com.transsion.makeupmode", Int::class.java)
        val stereoMode = RequestKey("com.transsion.stereomode", Int::class.java)
        val asdMode = RequestKey("com.transsion.asdMode", Int::class.java)
        val filterId = RequestKey("com.transsion.filterid", Int::class.java)
        val nightMode = RequestKey("com.transsion.nightMode", Int::class.java)
        val screenFlashMode = RequestKey("com.transsion.screenflashmode", Int::class.java)
        val magicSkyMode = RequestKey("com.transsion.magicskymode", Int::class.java)
        // TCAM's TranssionCameraMode / CamMode settings both land on this int[]:
        // CamMode → setAppModeId(value), TranssionCameraMode → 0/1 toggle.
        val transsionCameraMode = RequestKey("com.transsion.transsioncameramode", IntArray::class.java)
        val brightnessValue = RequestKey(
            "com.transsion.brightnessValue", Int::class.java, isSessionKey = true
        )
        // Probe: {1} when the HAL supports the transsion camera mode writes.
        val istrancamera = CharacteristicsKey("com.transsion.istrancamera", IntArray::class.java)

        val faceBeautyAvailable = CharacteristicsKey(
            "com.transsion.availableFaceBeautyMode", IntArray::class.java
        )
        val supportedFilterIds = CharacteristicsKey(
            "com.transsion.availableMaxFilterType", Int::class.java
        )
        val hdrSceneResult = ResultKey("com.transsion.hdrscene", Int::class.java)
        val faceInfoResult = ResultKey("com.transsion.faceinforesult", IntArray::class.java)
        val nightSceneResult = ResultKey("com.transsion.nighthawk", Int::class.java)
        val asdSceneResult = ResultKey("com.transsion.asdscene", Int::class.java)
    }
}
