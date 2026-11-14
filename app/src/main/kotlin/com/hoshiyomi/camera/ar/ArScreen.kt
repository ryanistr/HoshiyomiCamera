package com.hoshiyomi.camera.ar

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.opengl.GLSurfaceView

/**
 * AR mode viewfinder: an ARCore-backed GL surface replaces the Camera2 preview
 * (ARCore owns the camera for this mode), with the Compose chrome on top.
 * Tapping the scene hits-tests ARCore's planes and pins a marker anchor.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ArScreen() {
    var status by remember { mutableStateOf("Starting AR?") }
    var rendererRef: ArCoreRenderer? by remember { mutableStateOf(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    val renderer = ArCoreRenderer(context, this)
                    renderer.onTelemetry = { tracking, planes, anchors ->
                        status = when {
                            anchors > 0 -> "Tracking · ${anchors} marker(s) placed"
                            planes > 0 -> "Tracking · tap a detected surface"
                            tracking -> "Looking for surfaces…"
                            else -> "Point the camera around the room"
                        }
                    }
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    rendererRef = renderer
                }
            },
            // The GL surface consumes the touches itself so tap coordinates
            // arrive in view pixels for the plane hit test.
            modifier = Modifier.fillMaxSize().pointerInteropFilter { event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    rendererRef?.queueTap(event.x, event.y)
                }
                false
            }
        )

        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
        ) {
            Text(
                status,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> rendererRef?.pause()
                androidx.lifecycle.Lifecycle.Event.ON_START -> rendererRef?.resume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            rendererRef?.release()
        }
    }
}
