package com.nxfr.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color

// ── Instrument Deck Color Palette (Direction A) ───────────────────────
val DeckDark = Color(0xFF0B0F17)              // Root background (Cockpit Obsidian)
val DeckSurface = Color(0xFF131B26)           // Structural panel surface
val DeckSurfaceVariant = Color(0xFF1A2332)    // Elevated modules & cards
val DeckSurfaceContainer = Color(0xFF0F151F)  // Recessed panel wells
val DeckGridLine = Color(0xFF1E293B)          // Hairline structural dividers
val DeckGridLineBright = Color(0xFF334155)    // Active control outlines

val DeckTextPrimary = Color(0xFFF1F5F9)       // High-contrast readout text
val DeckTextSecondary = Color(0xFF94A3B8)     // Secondary telemetry labels
val DeckTextDim = Color(0xFF64748B)           // Inactive / prompt text

// ── Signal Tokens (Strictly functional, never decorative) ─────────────
val SignalBeam = Color(0xFF00E5FF)            // Active transmission cyan
val SignalBeamGlow = Color(0x3300E5FF)        // 20% alpha outer beam glow
val SignalStandby = Color(0xFF475569)         // Dormant wire slate
val SignalAlert = Color(0xFFFF3366)           // Breaker trip / Error coral
val SignalSuccess = Color(0xFF00E676)         // Cryptographic verification green
val SignalWarning = Color(0xFFFFB300)         // Action required amber

// ── Light Drafting-Paper Mode ─────────────────────────────────────────
val DeckPaper = Color(0xFFF8FAFC)
val DeckPaperSurface = Color(0xFFFFFFFF)
val DeckPaperSurfaceVariant = Color(0xFFF1F5F9)
val DeckPaperSurfaceContainer = Color(0xFFE2E8F0)
val DeckPaperGridLine = Color(0xFFCBD5E1)
val DeckPaperGridLineBright = Color(0xFF94A3B8)
val DeckPaperTextPrimary = Color(0xFF0F172A)
val DeckPaperTextSecondary = Color(0xFF475569)
val DeckPaperTextDim = Color(0xFF94A3B8)

// ── Legacy Token Aliases (Backward Compatibility) ─────────────────────
val ElectricCyan = SignalBeam
val AccessibleCyan = Color(0xFF00838F)
val DeepSlate = DeckDark
val WarmPaper = DeckPaper
val MintGreen = SignalSuccess
val LightSuccess = Color(0xFF2E7D32)
val CoralRed = SignalAlert
val LightErrorColor = Color(0xFFD32F2F)
val DarkSurface = DeckDark
val DarkSurfaceVariant = DeckSurface
val DarkSurfaceContainer = DeckSurfaceContainer
val DarkOnSurface = DeckTextPrimary
val DarkOnSurfaceVariant = DeckTextSecondary
val DarkOnPrimary = Color(0xFF003544)
val DarkPrimaryContainer = Color(0xFF004D63)
val DarkOnPrimaryContainer = Color(0xFF97F0FF)
val DarkSecondary = Color(0xFF818CF8)
val DarkSecondaryContainer = Color(0xFF312E81)
val DarkError = SignalAlert
val DarkErrorContainer = Color(0xFF93000A)
val DarkOutline = DeckGridLineBright
val DarkOutlineVariant = DeckGridLine

val LightSurface = DeckPaper
val LightSurfaceVariant = DeckPaperSurfaceVariant
val LightSurfaceContainer = DeckPaperSurfaceContainer
val LightOnSurface = DeckPaperTextPrimary
val LightOnSurfaceVariant = DeckPaperTextSecondary
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFCBF5FF)
val LightOnPrimaryContainer = Color(0xFF001F28)
val LightSecondary = Color(0xFF6366F1)
val LightSecondaryContainer = Color(0xFFE0E7FF)
val LightError = LightErrorColor
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOutline = DeckPaperGridLineBright
val LightOutlineVariant = DeckPaperGridLine

// ── Deck Colors Data Holder & CompositionLocal ────────────────────────
data class DeckColors(
    val rootBackground: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceContainer: Color,
    val gridLine: Color,
    val gridLineBright: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDim: Color,
    val signalBeam: Color,
    val signalBeamGlow: Color,
    val signalStandby: Color,
    val signalAlert: Color,
    val signalSuccess: Color,
    val signalWarning: Color,
    val isDark: Boolean
)

val DarkDeckColors = DeckColors(
    rootBackground = DeckDark,
    surface = DeckSurface,
    surfaceVariant = DeckSurfaceVariant,
    surfaceContainer = DeckSurfaceContainer,
    gridLine = DeckGridLine,
    gridLineBright = DeckGridLineBright,
    textPrimary = DeckTextPrimary,
    textSecondary = DeckTextSecondary,
    textDim = DeckTextDim,
    signalBeam = SignalBeam,
    signalBeamGlow = SignalBeamGlow,
    signalStandby = SignalStandby,
    signalAlert = SignalAlert,
    signalSuccess = SignalSuccess,
    signalWarning = SignalWarning,
    isDark = true
)

val LightDeckColors = DeckColors(
    rootBackground = DeckPaper,
    surface = DeckPaperSurface,
    surfaceVariant = DeckPaperSurfaceVariant,
    surfaceContainer = DeckPaperSurfaceContainer,
    gridLine = DeckPaperGridLine,
    gridLineBright = DeckPaperGridLineBright,
    textPrimary = DeckPaperTextPrimary,
    textSecondary = DeckPaperTextSecondary,
    textDim = DeckPaperTextDim,
    signalBeam = SignalBeam,
    signalBeamGlow = Color(0x3300838F),
    signalStandby = SignalStandby,
    signalAlert = Color(0xFFD32F2F),
    signalSuccess = Color(0xFF2E7D32),
    signalWarning = Color(0xFFED6C02),
    isDark = false
)

val OledDeckColors = DarkDeckColors.copy(
    rootBackground = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF000000),
    gridLine = Color(0xFF1E1E1E),
    gridLineBright = Color(0xFF383838),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA0AEC0),
    textDim = Color(0xFF718096),
    signalBeam = Color(0xFF00E5FF),
    signalBeamGlow = Color(0x3300E5FF),
    isDark = true
)

val LocalDeckColors = staticCompositionLocalOf { DarkDeckColors }

val MaterialTheme.deckColors: DeckColors
    @Composable
    @ReadOnlyComposable
    get() = LocalDeckColors.current
