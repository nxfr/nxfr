package com.nxfr.android.prefs

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NxfrPreferences {
    private const val PREFS_NAME = "nxfr_prefs"

    // Keys
    private const val KEY_SAVE_TO_GALLERY = "save_to_gallery"
    private const val KEY_SAVE_TO_HISTORY = "save_to_history"
    private const val KEY_AUTO_FINISH = "auto_finish"
    private const val KEY_COLLISION_RENAME = "collision_rename"
    private const val KEY_REQUIRE_PIN = "require_pin"
    private const val KEY_DEFAULT_SEND_MODE = "default_send_mode"
    private const val KEY_SHOW_CHECKSUM = "show_checksum"
    private const val KEY_ADVERTISE_MODE = "network_advertise_mode"
    private const val KEY_DEVICE_MODEL = "device_model_label"
    private const val KEY_PORT = "network_port"
    private const val KEY_TIMEOUT_MS = "discovery_timeout_ms"
    private const val KEY_MULTICAST = "multicast_address"

    // StateFlows
    private val _saveToGallery = MutableStateFlow(true)
    val saveToGallery: StateFlow<Boolean> = _saveToGallery.asStateFlow()

    private val _saveToHistory = MutableStateFlow(true)
    val saveToHistory: StateFlow<Boolean> = _saveToHistory.asStateFlow()

    private val _autoFinish = MutableStateFlow(false)
    val autoFinish: StateFlow<Boolean> = _autoFinish.asStateFlow()

    private val _collisionRename = MutableStateFlow(true)
    val collisionRename: StateFlow<Boolean> = _collisionRename.asStateFlow()

    private val _requirePin = MutableStateFlow("")
    val requirePin: StateFlow<String> = _requirePin.asStateFlow()

    private val _defaultSendMode = MutableStateFlow("single")
    val defaultSendMode: StateFlow<String> = _defaultSendMode.asStateFlow()

    private val _showChecksum = MutableStateFlow(true)
    val showChecksum: StateFlow<Boolean> = _showChecksum.asStateFlow()

    private val _advertiseMode = MutableStateFlow("all")
    val advertiseMode: StateFlow<String> = _advertiseMode.asStateFlow()

    private val _deviceModel = MutableStateFlow(Build.MODEL ?: "Android Device")
    val deviceModel: StateFlow<String> = _deviceModel.asStateFlow()

    private val _port = MutableStateFlow(17394)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _discoveryTimeoutMs = MutableStateFlow(5000L)
    val discoveryTimeoutMs: StateFlow<Long> = _discoveryTimeoutMs.asStateFlow()

    private val _multicastAddress = MutableStateFlow("224.0.0.251")
    val multicastAddress: StateFlow<String> = _multicastAddress.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _saveToGallery.value = prefs.getBoolean(KEY_SAVE_TO_GALLERY, true)
        _saveToHistory.value = prefs.getBoolean(KEY_SAVE_TO_HISTORY, true)
        _autoFinish.value = prefs.getBoolean(KEY_AUTO_FINISH, false)
        _collisionRename.value = prefs.getBoolean(KEY_COLLISION_RENAME, true)
        _requirePin.value = prefs.getString(KEY_REQUIRE_PIN, "") ?: ""
        _defaultSendMode.value = prefs.getString(KEY_DEFAULT_SEND_MODE, "single") ?: "single"
        _showChecksum.value = prefs.getBoolean(KEY_SHOW_CHECKSUM, true)
        _advertiseMode.value = prefs.getString(KEY_ADVERTISE_MODE, "all") ?: "all"
        _deviceModel.value = prefs.getString(KEY_DEVICE_MODEL, Build.MODEL ?: "Android Device") ?: (Build.MODEL ?: "Android Device")
        _port.value = prefs.getInt(KEY_PORT, 17394)
        _discoveryTimeoutMs.value = prefs.getLong(KEY_TIMEOUT_MS, 5000L)
        _multicastAddress.value = prefs.getString(KEY_MULTICAST, "224.0.0.251") ?: "224.0.0.251"
    }

    fun setSaveToGallery(context: Context, value: Boolean) {
        _saveToGallery.value = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SAVE_TO_GALLERY, value).apply()
    }

    fun setSaveToHistory(context: Context, value: Boolean) {
        _saveToHistory.value = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SAVE_TO_HISTORY, value).apply()
    }

    fun setAutoFinish(context: Context, value: Boolean) {
        _autoFinish.value = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_FINISH, value).apply()
    }

    fun setCollisionRename(context: Context, value: Boolean) {
        _collisionRename.value = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_COLLISION_RENAME, value).apply()
    }

    fun setRequirePin(context: Context, value: String) {
        _requirePin.value = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_REQUIRE_PIN, value).apply()
    }

    fun setDefaultSendMode(context: Context, value: String) {
        _defaultSendMode.value = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_DEFAULT_SEND_MODE, value).apply()
    }

    fun setShowChecksum(context: Context, value: Boolean) {
        _showChecksum.value = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_CHECKSUM, value).apply()
    }

    fun setAdvertiseMode(context: Context, value: String) {
        _advertiseMode.value = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_ADVERTISE_MODE, value).apply()
    }

    fun setDeviceModel(context: Context, value: String) {
        _deviceModel.value = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_DEVICE_MODEL, value).apply()
    }

    fun setPort(context: Context, value: Int) {
        _port.value = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_PORT, value).apply()
    }

    fun setDiscoveryTimeoutMs(context: Context, value: Long) {
        _discoveryTimeoutMs.value = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putLong(KEY_TIMEOUT_MS, value).apply()
    }

    fun setMulticastAddress(context: Context, value: String) {
        _multicastAddress.value = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_MULTICAST, value).apply()
    }

    fun resetNetworkDefaults(context: Context) {
        setPort(context, 17394)
        setDiscoveryTimeoutMs(context, 5000L)
        setMulticastAddress(context, "224.0.0.251")
    }
}
