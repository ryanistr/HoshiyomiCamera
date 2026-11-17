package com.hoshiyomi.camera

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.hoshiyomi.camera.ui.CameraScreen
import com.hoshiyomi.camera.ui.theme.StargazerTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Launch-for-result capture — the Stargazer counterpart of the reference camera app's
 * `CaptureActivity` + `IntentPhotoMode` / `IntentASDMode` / `IntentVideoMode`.
 *
 * `android.media.action.IMAGE_CAPTURE` (and our FANS parity action) land here
 * for stills; `android.media.action.VIDEO_CAPTURE` arrives through the
 * `com.android.camera.VideoCamera` alias. The caller's `output` URI, the
 * `return-data`/`crop` extras and the video quality are honored exactly as
 * the original's doAttach/attachToSaveUri/attachToInLinedIntent do, and a
 * plain back press returns RESULT_CANCELED like the original's onResult(0).
 */
class CaptureActivity : ComponentActivity() {
    private val viewModel by lazy {
        ViewModelProvider(this)[com.hoshiyomi.camera.ui.CameraViewModel::class.java]
    }

    /** Propagates the crop app's result back to the original caller. */
    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        setResult(result.resultCode, result.data)
        finish()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        val modeId = when (action) {
            "android.media.action.VIDEO_CAPTURE" -> "video"
            FANS_IMAGE_CAPTURE -> "asd" // IntentASDMode: intent-launched ASD stills
            else -> "photo"
        }
        val output = (intent?.extras?.get("output") as? Uri)
            ?: intent?.getStringExtra("output")?.let(Uri::parse)

        setContent { StargazerTheme { CameraScreen(
            onOpenScanner = {},
            onOpenSettings = {},
            onThumbnail = {},
            viewModel = viewModel
        ) } }

        viewModel.onResume()

        viewModel.setupIntentCapture(
            modeId = modeId,
            output = output,
            inline = output == null,
            crop = intent?.getStringExtra("crop")
        )
        lifecycleScope.launch {
            viewModel.intentResults.collect { result ->
                when {
                    !result.ok -> {
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                    result.data?.action == CROP_ACTION -> {
                        // the reference camera app attachToCropIntent: hand the temp JPEG to the
                        // crop flow with output/return-data; propagate back.
                        runCatching { cropLauncher.launch(result.data) }
                            .onFailure {
                                setResult(RESULT_CANCELED)
                                finish()
                            }
                    }
                    else -> {
                        setResult(RESULT_OK, result.data)
                        finish()
                    }
                }
            }
        }
    }

    private companion object {
        /** The AOSP crop flow the original forwards to (the reference camera app `CROP_INTENT`). */
        const val CROP_ACTION = "com.android.camera.action.CROP"
        /** Parity with the reference camera app's `android.media.action.FANS_IMAGE_CAPTURE` entry. */
        const val FANS_IMAGE_CAPTURE = "com.hoshiyomi.camera.action.FANS_IMAGE_CAPTURE"
    }
}
