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

/** Barcode scanner screen — QR decode runs through the Rust core. */
class BarcodeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StargazerTheme { ScannerRoute() }
        }
    }
}

@Composable
private fun ScannerRoute() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text("Scanner", color = MaterialTheme.colorScheme.onPrimary)
    }
}
