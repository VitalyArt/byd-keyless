package com.vitalyart.bydkeyless.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

internal val Night: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val PanelRaised: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
internal val Electric: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val ElectricDark: Color @Composable get() = MaterialTheme.colorScheme.onPrimary
internal val TextPrimary: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val TextSecondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val Critical: Color @Composable get() = MaterialTheme.colorScheme.error
internal val StrokeColor: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant

@Composable internal fun KeylessTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val colors = if (dark) darkColorScheme(primary = Color(0xFFAAC7FF), onPrimary = Color(0xFF14345E),
        secondary = Color(0xFFAAC7FF), secondaryContainer = Color(0xFF263B57), onSecondaryContainer = Color(0xFFD5E3FF),
        background = Color(0xFF111318), surface = Color(0xFF191C22), surfaceVariant = Color(0xFF252A33),
        onSurface = Color(0xFFE5E7ED), onSurfaceVariant = Color(0xFFBBC2CF), outlineVariant = Color(0xFF373D48))
    else lightColorScheme(primary = Color(0xFF315D96), onPrimary = Color.White,
        secondary = Color(0xFF315D96), secondaryContainer = Color(0xFFDCE8F8), onSecondaryContainer = Color(0xFF173B68),
        background = Color(0xFFF8F9FC), surface = Color.White, surfaceVariant = Color(0xFFEDF0F5),
        onSurface = Color(0xFF191C22), onSurfaceVariant = Color(0xFF505C6E), outlineVariant = Color(0xFFDCE1E9))
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = if (dark) 0xFF111318.toInt() else 0xFFF8F9FC.toInt()
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
