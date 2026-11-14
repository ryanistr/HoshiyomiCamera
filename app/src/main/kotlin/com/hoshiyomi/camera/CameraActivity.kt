package com.hoshiyomi.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.hoshiyomi.camera.ui.CameraScreen
import com.hoshiyomi.camera.ui.theme.StargazerTheme

class CameraActivity : ComponentActivity() {
    private val viewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[com.hoshiyomi.camera.ui.CameraViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StargazerTheme { CameraRoute() } }
        // Route the OEM deep-link action that the original app exposes from
        // launcher shortcuts: START_SPECIFY_MODE with a mode id jumps straight
        // into that mode.
        if (intent?.action == ACTION_START_SPECIFY_MODE) {
            intent.getStringExtra(EXTRA_MODE)?.let { modeId ->
                viewModel.queueModeOnStart(modeId)
            }
        }
    }

    private companion object {
        const val ACTION_START_SPECIFY_MODE = "com.hoshiyomi.camera.action.START_SPECIFY_MODE"
        const val EXTRA_MODE = "mode"
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }
}

@Composable
private fun CameraRoute() {
    CameraScreen(
        onOpenScanner = { /* the scanner mode is reachable from the mode chips */ },
        onOpenSettings = { /* settings sheet — see SettingsActivity */ },
        onThumbnail = { }
    )
}
