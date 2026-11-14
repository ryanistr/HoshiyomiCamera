package com.hoshiyomi.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Professional-mode dials — ISO, shutter speed (exposure time) and focus
 * distance, mapped onto the Camera2 manual 3A controls by
 * [com.hoshiyomi.camera.ui.CameraViewModel.setProControls].
 *
 * The stack sits along the right of the viewfinder; each dial defaults to the
 * auto end and only takes over once the user moves it.
 */
@Composable
fun ProDials(
    isoRange: IntArray?,
    isoValue: Float,
    onIso: (Float) -> Unit,
    shutterRange: LongArray?,
    shutterValue: Float,
    onShutter: (Float) -> Unit,
    focusMin: Float,
    focusValue: Float,
    onFocus: (Float) -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(16.dp, 0.dp, 0.dp, 16.dp),
            modifier = Modifier
                .fillMaxWidth(0.32f)
                .padding(vertical = 110.dp)
        ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Dial(label = "ISO", text = isoText(isoValue), value = isoValue,
                    enabled = isoRange != null,
                    from = isoRange?.get(0)?.toFloat() ?: 100f,
                    to = isoRange?.get(1)?.toFloat() ?: 1600f, onValue = onIso)
                Dial(label = "SEC", text = shutterText(shutterValue), value = shutterValue,
                    enabled = shutterRange != null, from = 0f, to = 1f, onValue = onShutter)
                Dial(label = "FOCUS", text = focusText(focusValue), value = focusValue,
                    enabled = focusMin > 0f, from = 0f,
                    to = focusMin.coerceAtLeast(0.1f), onValue = onFocus)
            }
        }
    }
}

@Composable
private fun Dial(
    label: String,
    text: String,
    value: Float,
    enabled: Boolean,
    from: Float,
    to: Float,
    onValue: (Float) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$label $text", style = MaterialTheme.typography.labelSmall, color = Color.White)
        Slider(
            value = value.coerceIn(from, to),
            onValueChange = onValue,
            valueRange = from..to,
            enabled = enabled
        )
    }
}

private fun isoText(v: Float): String = if (v <= 0.5f) "AUTO" else "${v.toInt()}"

private fun shutterText(v: Float): String =
    if (v <= 0.01f) "AUTO" else "%.3f".format(v)

private fun focusText(v: Float): String =
    if (v <= 0.01f) "AUTO" else "%.1f".format(v)
