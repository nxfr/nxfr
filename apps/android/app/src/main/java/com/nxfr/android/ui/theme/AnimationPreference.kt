package com.nxfr.android.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AnimationPreference {
    private const val PREFS_NAME = "nxfr_prefs"
    private const val KEY_ANIMATIONS_ENABLED = "animations_enabled"

    private val _animationsEnabled = MutableStateFlow(true)
    val animationsEnabled: StateFlow<Boolean> = _animationsEnabled.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _animationsEnabled.value = prefs.getBoolean(KEY_ANIMATIONS_ENABLED, true)
    }

    fun setAnimationsEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ANIMATIONS_ENABLED, enabled).apply()
        _animationsEnabled.value = enabled
    }

    fun isSystemAnimationDisabled(context: Context): Boolean {
        return try {
            val durationScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            durationScale == 0f
        } catch (_: Throwable) {
            false
        }
    }
}

val LocalAnimationsEnabled = staticCompositionLocalOf { true }

@Composable
fun isAnimationEffective(): Boolean {
    val context = LocalContext.current
    val appPref by AnimationPreference.animationsEnabled.collectAsState()
    val systemDisabled = AnimationPreference.isSystemAnimationDisabled(context)
    return appPref && !systemDisabled
}
