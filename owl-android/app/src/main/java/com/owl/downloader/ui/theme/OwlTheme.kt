package com.owl.downloader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Reconstructs `com.junkfood.seal.ui.theme.ShapeKt`, `TypeKt`, and `com.kyant.monet`
 * dynamic Material 3 color palettes for Owl.
 */
private val OwlDarkColorScheme = darkColorScheme(
    primary = Color(0xFF7AD0FF),
    onPrimary = Color(0xFF003549),
    primaryContainer = Color(0xFF004C68),
    onPrimaryContainer = Color(0xFFC3E8FF),
    secondary = Color(0xFFB5CAD6),
    onSecondary = Color(0xFF20333D),
    secondaryContainer = Color(0xFF364954),
    onSecondaryContainer = Color(0xFFD1E6F3),
    tertiary = Color(0xFFCAC1E9),
    surface = Color(0xFF0F1417),
    surfaceVariant = Color(0xFF252B2E),
    onSurface = Color(0xFFDFE3E7),
    onSurfaceVariant = Color(0xFFC0C8CD),
    outline = Color(0xFF8A9297),
    error = Color(0xFFFFB4AB)
)

private val OwlLightColorScheme = lightColorScheme(
    primary = Color(0xFF00668B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC3E8FF),
    onPrimaryContainer = Color(0xFF001E2C),
    secondary = Color(0xFF4E616C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1E6F3),
    onSecondaryContainer = Color(0xFF091E27),
    surface = Color(0xFFF6FAFE),
    onSurface = Color(0xFF181C1F)
)

val OwlShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun OwlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> OwlDarkColorScheme
        else -> OwlLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = OwlShapes,
        content = content
    )
}
