package com.nxfr.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.nxfr.android.R

// ── Bold Identity Color Schemes ───────────────────────────────────────
val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_bold, FontWeight.Bold),
)

// ── Typography Scale ──────────────────────────────────────────────────
private val NxfrTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold),
        displaySmall = base.displaySmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal),
        bodyMedium = base.bodyMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal),
        bodySmall = base.bodySmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal),
        labelLarge = base.labelLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal),
    )
}

// ── Shapes (8/12/16/24 dp tokens) ─────────────────────────────────────
private val NxfrShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// ── Bold Identity Color Schemes ───────────────────────────────────────
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

val OledDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = DarkOnPrimary,
    primaryContainer = Color(0xFF002233),
    onPrimaryContainer = ElectricCyan,
    secondary = DarkSecondary,
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF111111),
    surfaceContainer = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFAAAAAA),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    outline = Color(0xFF333333)
)

fun generateCustomColorScheme(seedColor: Color, darkTheme: Boolean): ColorScheme {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seedColor.toArgb(), hsv)

    val primary = seedColor
    val onPrimary = if (hsv[2] > 0.6f && hsv[1] < 0.5f) Color.Black else Color.White

    return if (darkTheme) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primary.copy(alpha = 0.25f),
            onPrimaryContainer = primary,
            surface = Color(0xFF0F172A),
            surfaceVariant = Color(0xFF1E293B),
            surfaceContainer = Color(0xFF0F172A),
            onSurface = Color(0xFFF8FAFC),
            onSurfaceVariant = Color(0xFF94A3B8),
            background = Color(0xFF0F172A),
            onBackground = Color(0xFFF8FAFC),
            outline = Color(0xFF334155)
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primary.copy(alpha = 0.15f),
            onPrimaryContainer = primary,
            surface = Color(0xFFF8FAFC),
            surfaceVariant = Color(0xFFF1F5F9),
            surfaceContainer = Color(0xFFF8FAFC),
            onSurface = Color(0xFF0F172A),
            onSurfaceVariant = Color(0xFF64748B),
            background = Color(0xFFFFFFFF),
            onBackground = Color(0xFF0F172A),
            outline = Color(0xFFCBD5E1)
        )
    }
}

// ── Theme Composable ──────────────────────────────────────────────────
@Composable
fun NxfrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorMode by ThemePreference.colorMode.collectAsState()
    val customSeedArgb by ThemePreference.customSeedColor.collectAsState()

    val colorScheme = when {
        colorMode == ThemePreference.COLOR_MODE_OLED && darkTheme -> OledDarkColorScheme
        colorMode == ThemePreference.COLOR_MODE_CUSTOM -> generateCustomColorScheme(Color(customSeedArgb), darkTheme)
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

    val deckColors = when {
        colorMode == ThemePreference.COLOR_MODE_OLED && darkTheme -> OledDeckColors
        darkTheme -> DarkDeckColors
        else -> LightDeckColors
    }

    val isAnimated = isAnimationEffective()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAnimationsEnabled provides isAnimated,
        LocalDeckColors provides deckColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NxfrTypography,
            shapes = NxfrShapes,
            content = content
        )
    }
}
