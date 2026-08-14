package com.nxfr.android.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists theme preference, color mode & custom palette in SharedPreferences. */
object ThemePreference {
    private const val PREFS_NAME = "nxfr_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_COLOR_MODE = "color_mode"
    private const val KEY_CUSTOM_SEED_COLOR = "custom_seed_color"
    
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    const val COLOR_MODE_BRAND = "brand"
    const val COLOR_MODE_OLED = "oled"
    const val COLOR_MODE_CUSTOM = "custom"

    private val _themeMode = MutableStateFlow(SYSTEM)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _colorMode = MutableStateFlow(COLOR_MODE_BRAND)
    val colorMode: StateFlow<String> = _colorMode.asStateFlow()

    private val _customSeedColor = MutableStateFlow(0xFF00E5FF.toInt())
    val customSeedColor: StateFlow<Int> = _customSeedColor.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _themeMode.value = prefs.getString(KEY_THEME, SYSTEM) ?: SYSTEM
        _colorMode.value = prefs.getString(KEY_COLOR_MODE, COLOR_MODE_BRAND) ?: COLOR_MODE_BRAND
        _customSeedColor.value = prefs.getInt(KEY_CUSTOM_SEED_COLOR, 0xFF00E5FF.toInt())
    }

    fun setTheme(context: Context, mode: String) {
        _themeMode.value = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, mode).apply()
    }

    fun setColorMode(context: Context, mode: String) {
        _colorMode.value = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_COLOR_MODE, mode).apply()
    }

    fun setCustomSeedColor(context: Context, colorArgb: Int) {
        _customSeedColor.value = colorArgb
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CUSTOM_SEED_COLOR, colorArgb).apply()
    }
}
