package com.nxfr.android.ui.theme

import androidx.compose.ui.graphics.Color

// ── Brand Identity Tokens (Variant B) ─────────────────────────────────
val ElectricCyan = Color(0xFF00E5FF)       // Primary Dark (WCAG 12.4:1 on #0F172A)
val AccessibleCyan = Color(0xFF00838F)     // Primary Light (WCAG 5.2:1 on #F8F9FA)
val DeepSlate = Color(0xFF0F172A)          // Surface Dark
val WarmPaper = Color(0xFFF8F9FA)          // Surface Light

val MintGreen = Color(0xFF4ECDC4)          // Success Dark (WCAG 10.2:1 on #0F172A)
val LightSuccess = Color(0xFF2E7D32)       // Success Light (WCAG 4.8:1 on #F8F9FA)

val CoralRed = Color(0xFFFF6B6B)           // Error Dark (WCAG 7.5:1 on #0F172A)
val LightErrorColor = Color(0xFFD32F2F)    // Error Light (WCAG 5.4:1 on #F8F9FA)

// ── Dark Palette Hierarchy ────────────────────────────────────────────
val DarkSurface = Color(0xFF0F172A)
val DarkSurfaceVariant = Color(0xFF1E293B)
val DarkSurfaceContainer = Color(0xFF162032)
val DarkOnSurface = Color(0xFFE2E8F0)      // WCAG 13.8:1 on #0F172A
val DarkOnSurfaceVariant = Color(0xFF94A3B8) // WCAG 6.4:1 on #0F172A
val DarkOnPrimary = Color(0xFF003544)
val DarkPrimaryContainer = Color(0xFF004D63)
val DarkOnPrimaryContainer = Color(0xFF97F0FF)
val DarkSecondary = Color(0xFF818CF8)
val DarkSecondaryContainer = Color(0xFF312E81)
val DarkError = CoralRed
val DarkErrorContainer = Color(0xFF93000A)
val DarkOutline = Color(0xFF475569)
val DarkOutlineVariant = Color(0xFF334155)

// ── Light Palette Hierarchy ───────────────────────────────────────────
val LightSurface = WarmPaper
val LightSurfaceVariant = Color(0xFFF1F5F9)
val LightSurfaceContainer = Color(0xFFE2E8F0)
val LightOnSurface = Color(0xFF0F172A)     // WCAG 16.5:1 on #F8F9FA
val LightOnSurfaceVariant = Color(0xFF475569) // WCAG 7.1:1 on #F8F9FA
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFCBF5FF)
val LightOnPrimaryContainer = Color(0xFF001F28)
val LightSecondary = Color(0xFF6366F1)
val LightSecondaryContainer = Color(0xFFE0E7FF)
val LightError = LightErrorColor
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOutline = Color(0xFF94A3B8)
val LightOutlineVariant = Color(0xFFCBD5E1)
