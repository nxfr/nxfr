package com.nxfr.android.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists theme preference in SharedPreferences. */
object ThemePreference {
    private const val PREFS_NAME = "nxfr_prefs"
    private const val KEY_THEME = "theme_mode"
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private val _themeMode = MutableStateFlow(SYSTEM)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _themeMode.value = prefs.getString(KEY_THEME, SYSTEM) ?: SYSTEM
    }

    fun setTheme(context: Context, mode: String) {
        _themeMode.value = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, mode).apply()
    }
}
