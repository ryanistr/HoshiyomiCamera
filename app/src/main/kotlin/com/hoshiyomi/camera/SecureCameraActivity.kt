package com.hoshiyomi.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.hoshiyomi.camera.ui.theme.StargazerTheme

/**
 * Lock-screen camera — mirrors the manifest split in the original APK between
 * `CameraActivity` and `SecureCameraActivity`.
 */
class SecureCameraActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StargazerTheme { SecureRoute() }
        }
    }
}

@Composable
private fun SecureRoute() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text("Stargazer · secure camera", color = MaterialTheme.colorScheme.onPrimary)
    }
}
