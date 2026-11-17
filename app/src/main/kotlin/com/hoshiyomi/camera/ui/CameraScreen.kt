package com.hoshiyomi.camera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * The camera screen: viewfinder + mode chips + shutter, Material 3 styled.
 *
 * The viewfinder is a [TextureView] handed to [CameraViewModel]'s
 * [PlatformCamera], which builds the Camera2 session over its Surface — the
 * same multi-surface session organization the original app uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onOpenScanner: () -> Unit,
    onOpenSettings: () -> Unit,
    onThumbnail: () -> Unit,
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()

    var hasCameraPermission by remember {
        androidx.compose.runtime.mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var hasAudioPermission by remember {
        androidx.compose.runtime.mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCameraPermission = result[Manifest.permission.CAMERA] == true
        hasAudioPermission = result[Manifest.permission.RECORD_AUDIO] == true
        if (hasCameraPermission) viewModel.start()
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            viewModel.start()
            if (!hasAudioPermission) {
                permissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        } else {
            permissionsLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    var showSettings by remember { androidx.compose.runtime.mutableStateOf(false) }
    val selections = rememberSettingSelections()
    DisposableLifecycle(lifecycle, onStart = { viewModel.start() }, onStop = { viewModel.stop() })

    if (showSettings) {
        SettingsSheet(
            mode = state.activeMode,
            selections = selections,
            onSelect = { key, index ->
                selections[key] = index
                viewModel.onSettingSelected(key, index)
            },
            onDismiss = { showSettings = false }
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasCameraPermission) {
            PermissionRequest(
                onRequest = {
                    permissionsLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    )
                }
            )
            return@Box
        }

        // AR mode removed by user decision — Camera2 viewfinder always shown.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val ratio = remember(state.activeMode.id, state.facingFront, state.ready) {
                viewModel.previewDisplayRatio()
            }
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                surface: SurfaceTexture, width: Int, height: Int
                            ) {
                                viewModel.attachPreview(surface, width, height)
                            }

                            override fun onSurfaceTextureSizeChanged(
                                surface: SurfaceTexture, width: Int, height: Int
                            ) {
                                viewModel.previewSizeChanged(width, height)
                            }

                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                viewModel.detachPreview()
                                return true
                            }

                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio)
            )
        }

        // Top chrome + filter picker, stacked clear of the status bar: the
        // chip row used to sit at y=0, where the system swallowed the touches.
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                }
                Text(
                    text = if (state.isCapturing) "Saving…" else state.activeMode.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
                IconButton(onClick = { viewModel.flipCamera() }) {
                    Icon(Icons.Default.Cameraswitch, "Switch camera", tint = Color.White)
                }
            }

            // Filter style picker (photo capture modes).
            if (state.activeMode.id == "photo" || state.activeMode.id == "portrait") {
                FilterStylePicker(
                    active = state.filterStyle,
                    onSelect = viewModel::setFilterStyle
                )
            }
        }

        if (state.faces.isNotEmpty() && state.activeMode.id != "professional") {
            FaceHud(faces = state.faces)
        }

        // Scene hint from vendor ASD/Night result keys.
        state.sceneHint?.let { hint ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(top = 96.dp)
                ) {
                    Text(
                        hint,
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        // Live filter preview thumbnail (Rust color-matrix pipeline).
        state.filterPreview?.let { bmp ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(start = 16.dp, bottom = 120.dp)
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Filter preview",
                        modifier = Modifier.size(96.dp, 72.dp)
                    )
                }
            }
        }

        if (state.activeMode.id == "panorama") {
            PanoramaGuide(
                capturing = state.isCapturing,
                framesCaptured = state.panoProgress,
                frameCount = 3
            )
        }

        if (state.activeMode.id == "professional") {
            ProDials(
                isoRange = state.isoRange,
                isoValue = state.proIso,
                onIso = viewModel::setProIso,
                shutterRange = state.shutterRange,
                shutterValue = state.proShutter,
                onShutter = viewModel::setProShutter,
                focusMin = state.focusMin,
                focusValue = state.proFocus,
                onFocus = viewModel::setProFocus
            )
        }

        // Bottom controls
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (state.isCapturing) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
            }
            if (state.lastQr != null) {
                QrResultChip(text = state.lastQr!!, onClear = viewModel::clearQr)
                Spacer(Modifier.height(16.dp))
            }
            // The intent-capture activity has no mode grid — the caller picked
            // the capture kind (the reference camera app's CaptureActivity hosts IntentPhotoMode).
            if (!state.isIntentMode) {
                ModePicker(
                    modes = state.modes,
                    activeId = state.activeMode.id,
                    onSelect = viewModel::selectMode
                )
            }
            Spacer(Modifier.height(24.dp))
            ShutterRow(
                isVideo = state.activeMode.isVideo,
                isRecording = state.isRecording,
                onShutter = viewModel::onShutter,
                onOpenScanner = onOpenScanner,
                canCapture = state.ready,
                intentMode = state.isIntentMode,
                onCancel = viewModel::cancelIntentCapture
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // Intent still review: captured, waiting for the caller's confirm — the
    // original's IntentReviewUI (retake / done over the full-frame preview).
    state.intentReview?.let { review ->
        IntentReviewOverlay(
            bitmap = review,
            onRetake = viewModel::retakeIntentCapture,
            onConfirm = viewModel::confirmIntentCapture
        )
    }
}

@Composable
private fun IntentReviewOverlay(
    bitmap: android.graphics.Bitmap,
    onRetake: () -> Unit,
    onConfirm: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f))) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured photo",
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onRetake) {
                Text("Retake", color = Color.White)
            }
            FilledTonalButton(onClick = onConfirm) {
                Text("OK")
            }
        }
    }
}

@Composable
private fun ModePicker(
    modes: List<com.hoshiyomi.camera.mode.CameraMode>,
    activeId: String,
    onSelect: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val activeIndex = modes.indexOfFirst { it.id == activeId }
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 32.dp)
    ) {
        items(modes, key = { it.id }) { mode ->
            val selected = mode.id == activeId
            AssistChip(
                onClick = { onSelect(mode.id) },
                label = { Text(mode.title) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (selected)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    else Color.Black.copy(alpha = 0.5f),
                    labelColor = Color.White
                ),
                border = null
            )
        }
    }
}

@Composable
private fun ShutterRow(
    isVideo: Boolean,
    isRecording: Boolean,
    onShutter: () -> Unit,
    onOpenScanner: () -> Unit,
    canCapture: Boolean,
    intentMode: Boolean = false,
    onCancel: () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        if (intentMode) {
            // Intent capture replaces the scanner slot with a cancel control —
            // the caller expects a result even when the user backs out.
            OutlinedIconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, "Cancel", tint = Color.White)
            }
        } else {
            OutlinedIconButton(onClick = onOpenScanner, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.QrCodeScanner, "Scanner", tint = Color.White)
            }
        }
        FilledIconButton(
            onClick = onShutter,
            enabled = canCapture,
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isVideo && isRecording)
                    MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        ) {
            Box(
                Modifier.size(if (isVideo && isRecording) 28.dp else 56.dp)
                    .background(Color.White, RoundedCornerShape(12.dp))
            )
        }
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun QrResultChip(text: String, onClear: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text, color = MaterialTheme.colorScheme.onSecondaryContainer)
            IconButton(onClick = onClear) {
                Text("×", color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Stargazer needs the camera to work.",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRequest,
            shape = RoundedCornerShape(24.dp)
        ) {
            Text("Grant permission", Modifier.padding(16.dp, 8.dp))
        }
    }
}

/** Filter style chips — the original's TranssionFilterEntry catalog. */
@Composable
private fun FilterStylePicker(active: String?, onSelect: (String?) -> Unit) {
    val styles = listOf("none", "warm", "cool", "mono", "vintage")
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 32.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(styles, key = { it }) { style ->
            FilterChip(
                selected = active == style,
                onClick = { onSelect(if (style == "none") null else style) },
                label = { Text(style.replaceFirstChar { it.uppercase() }) }
            )
        }
    }
}

/**
 * Panorama guidance: tells the user to pan slowly while the Rust pipeline
 * collects and stitches frames.
 */
@Composable
private fun PanoramaGuide(capturing: Boolean, framesCaptured: Int, frameCount: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (capturing) "Panning… keep moving steadily ($framesCaptured/$frameCount)" else "Press the shutter, then pan slowly",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (capturing) {
                    LinearProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * Face HUD: draws normalized face boxes over the viewfinder. Box coordinates
 * arrive from the Rust face detector on the analysis stream.
 */
@Composable
private fun FaceHud(faces: List<FloatArray>) {
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxSize()
    ) {
        val canvasW = size.width
        val canvasH = size.height
        for (face in faces) {
            if (face.size < 4) continue
            val (nx, ny, nw, nh) = face
            val left = nx * canvasW
            val top = ny * canvasH
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 3.dp.toPx()
            )
            drawRect(
                color = androidx.compose.ui.graphics.Color(0xFFE6E0E9).copy(alpha = 0.9f),
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(nw * canvasW, nh * canvasH),
                style = stroke
            )
        }
    }
}

@Composable
private fun DisposableLifecycle(
    lifecycle: androidx.lifecycle.Lifecycle,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = object : androidx.lifecycle.LifecycleObserver {
            @androidx.lifecycle.OnLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
            fun started() = onStart()

            @androidx.lifecycle.OnLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
            fun stopped() = onStop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
