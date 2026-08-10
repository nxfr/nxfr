package com.nxfr.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.nxfr.android.R

// ── Inter font family (static bundle, SIL OFL) ───────────────────────
val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_bold, FontWeight.Bold),
)

// ── Typography ────────────────────────────────────────────────────────
private val NxfrTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = InterFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = InterFontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = InterFontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = InterFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = InterFontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = InterFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = InterFontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = InterFontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = InterFontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontFamily = InterFontFamily),
    )
}

// ── Shapes ────────────────────────────────────────────────────────────
private val NxfrShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// ── Color schemes ─────────────────────────────────────────────────────
private val NxfrDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    secondaryContainer = DarkSecondaryContainer,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    surfaceContainer = DarkSurfaceContainer,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = DarkError,
    errorContainer = DarkErrorContainer,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    background = DarkSurface,
    onBackground = DarkOnSurface,
)

private val NxfrLightColorScheme = lightColorScheme(
    primary = AccessibleCyan,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    secondaryContainer = LightSecondaryContainer,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    surfaceContainer = LightSurfaceContainer,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = LightError,
    errorContainer = LightErrorContainer,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    background = LightSurface,
    onBackground = LightOnSurface,
)

// ── Theme composable ──────────────────────────────────────────────────
@Composable
fun NxfrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> NxfrDarkColorScheme
        else -> NxfrLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NxfrTypography,
        shapes = NxfrShapes,
        content = content
    )
}
