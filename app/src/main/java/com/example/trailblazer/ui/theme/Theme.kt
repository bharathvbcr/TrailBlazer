package com.example.trailblazer.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.trailblazer.data.ThemeMode

private val ForestLight = lightColorScheme(
    primary = Color(0xFF2E6B45),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2F1C3),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF3B6377),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBFE8FF),
    onSecondaryContainer = Color(0xFF001F2A),
    tertiary = Color(0xFF8A5100),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFF5FBF4),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFF5FBF4),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDCE5DB),
    onSurfaceVariant = Color(0xFF414942),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF5EE),
    surfaceContainer = Color(0xFFE9EFE8),
    surfaceContainerHigh = Color(0xFFE4EAE3),
    surfaceContainerHighest = Color(0xFFDEE4DD),
    outline = Color(0xFF717971),
    outlineVariant = Color(0xFFC0C9BF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val ForestDark = darkColorScheme(
    primary = Color(0xFF96D5A8),
    onPrimary = Color(0xFF00391C),
    primaryContainer = Color(0xFF12512F),
    onPrimaryContainer = Color(0xFFB2F1C3),
    secondary = Color(0xFFA3CCE3),
    onSecondary = Color(0xFF033547),
    secondaryContainer = Color(0xFF224B5E),
    onSecondaryContainer = Color(0xFFBFE8FF),
    tertiary = Color(0xFFFFB871),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF0E1512),
    onBackground = Color(0xFFDEE4DD),
    surface = Color(0xFF0E1512),
    onSurface = Color(0xFFDEE4DD),
    surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC0C9BF),
    surfaceContainerLowest = Color(0xFF09100C),
    surfaceContainerLow = Color(0xFF171D19),
    surfaceContainer = Color(0xFF1B211D),
    surfaceContainerHigh = Color(0xFF252B27),
    surfaceContainerHighest = Color(0xFF303632),
    outline = Color(0xFF8A938A),
    outlineVariant = Color(0xFF414942),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * Night-vision palette: only deep reds on black, so the screen does not ruin dark adaptation.
 * Implemented as a colour scheme (not a filter), so every component, including dialogs, follows it.
 */
private val NightRed = darkColorScheme(
    primary = Color(0xFFFF5A4F),
    onPrimary = Color(0xFF1A0000),
    primaryContainer = Color(0xFF4A0A06),
    onPrimaryContainer = Color(0xFFFF8A80),
    secondary = Color(0xFFD9443A),
    onSecondary = Color(0xFF1A0000),
    secondaryContainer = Color(0xFF3A0703),
    onSecondaryContainer = Color(0xFFFF8A80),
    tertiary = Color(0xFFFF7A6E),
    onTertiary = Color(0xFF1A0000),
    tertiaryContainer = Color(0xFF3A0703),
    onTertiaryContainer = Color(0xFFFF8A80),
    background = Color.Black,
    onBackground = Color(0xFFE0453B),
    surface = Color.Black,
    onSurface = Color(0xFFE0453B),
    surfaceVariant = Color(0xFF2A0402),
    onSurfaceVariant = Color(0xFFB8352C),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF120100),
    surfaceContainer = Color(0xFF180200),
    surfaceContainerHigh = Color(0xFF200301),
    surfaceContainerHighest = Color(0xFF2A0402),
    outline = Color(0xFF7A1C15),
    outlineVariant = Color(0xFF3A0703),
    error = Color(0xFFFF8A80),
    onError = Color.Black,
)

/** Extra semantic colours that Material does not have: good/caution/danger for readings. */
data class StatusColors(val good: Color, val caution: Color, val danger: Color, val isNight: Boolean)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(Color(0xFF2E7D32), Color(0xFFB26A00), Color(0xFFC62828), false) }

private val Numeric = TextStyle(fontFeatureSettings = "tnum")

private fun typography(): Typography {
    val t = Typography()
    return t.copy(
        displayLarge = t.displayLarge.merge(Numeric).copy(fontWeight = FontWeight.Light),
        displayMedium = t.displayMedium.merge(Numeric).copy(fontWeight = FontWeight.Light),
        displaySmall = t.displaySmall.merge(Numeric),
        headlineLarge = t.headlineLarge.merge(Numeric),
        headlineMedium = t.headlineMedium.merge(Numeric),
        headlineSmall = t.headlineSmall.merge(Numeric),
        titleLarge = t.titleLarge.merge(Numeric).copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.merge(Numeric),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun TrailTheme(
    mode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    nightRed: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        nightRed -> NightRed
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> ForestDark
        else -> ForestLight
    }
    val status = when {
        nightRed -> StatusColors(Color(0xFFFF5A4F), Color(0xFFFF7A6E), Color(0xFFFF8A80), true)
        dark -> StatusColors(Color(0xFF81C995), Color(0xFFFFB871), Color(0xFFFFB4AB), false)
        else -> StatusColors(Color(0xFF2E7D32), Color(0xFF9A5B00), Color(0xFFBA1A1A), false)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = ctx.findActivity()?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !dark && !nightRed
            insetsController.isAppearanceLightNavigationBars = !dark && !nightRed
        }
    }

    CompositionLocalProvider(
        LocalContentColor provides scheme.onBackground,
        LocalStatusColors provides status,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography()) {
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent,
                    contentColor = scheme.onBackground,
                    content = content,
                )
            }
        }
    }
}

