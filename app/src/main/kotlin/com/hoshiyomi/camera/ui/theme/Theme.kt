package com.hoshiyomi.camera.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Stargazer seed: deep night-sky indigo running against warm starlight amber.
private val Indigo = Color(0xFF4C55B0)
private val IndigoDark = Color(0xFFC3C7FF)
private val Amber = Color(0xFFB58400)
private val AmberDark = Color(0xFFFFE08E)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E0FF),
    onPrimaryContainer = Color(0xFF00096C),
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE8C8),
    onSecondaryContainer = Color(0xFF2A1A00),
    surface = Color(0xFFFDF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFFF2EDF7),
    surfaceContainerHigh = Color(0xFFEBE6F2),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
)

private val DarkColors = darkColorScheme(
    primary = IndigoDark,
    onPrimary = Color(0xFF131B62),
    primaryContainer = Color(0xFF333489),
    onPrimaryContainer = Color(0xFFE2E0FF),
    secondary = AmberDark,
    onSecondary = Color(0xFF442E00),
    secondaryContainer = Color(0xFF614400),
    onSecondaryContainer = Color(0xFFFFE8C8),
    surface = Color(0xFF121218),
    onSurface = Color(0xFFE5E1E9),
    surfaceContainer = Color(0xFF1D1B24),
    surfaceContainerHigh = Color(0xFF28262F),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46464F),
)

@Composable
fun StargazerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val scheme = when {
        // Camera UI is nearly always shown in the dark scheme: the majority of
        // the screen is the live viewfinder, and light chrome would blind.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            dynamicDarkColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
