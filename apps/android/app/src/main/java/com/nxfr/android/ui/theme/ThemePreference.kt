package com.nxfr.android.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists theme preference & palette style in SharedPreferences. */
object ThemePreference {
    private const val PREFS_NAME = "nxfr_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color_enabled"
    
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private val _themeMode = MutableStateFlow(SYSTEM)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _useDynamicColor = MutableStateFlow(false) // Default: Bold Identity brand palette
    val useDynamicColor: StateFlow<Boolean> = _useDynamicColor.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _themeMode.value = prefs.getString(KEY_THEME, SYSTEM) ?: SYSTEM
        _useDynamicColor.value = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
    }

    fun setTheme(context: Context, mode: String) {
        _themeMode.value = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, mode).apply()
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        _useDynamicColor.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }
}
