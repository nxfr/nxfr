package com.nxfr.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nxfr.android.R
import com.nxfr.android.prefs.NxfrPreferences
import com.nxfr.android.service.NxfrService
import com.nxfr.android.ui.dialogs.ColorPickerDialog
import com.nxfr.android.ui.sheets.TroubleshootSheet
import com.nxfr.android.ui.theme.ThemePreference
import com.nxfr.android.utils.DebugBundleExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    deviceName: String,
    deviceId: String,
    onDeviceNameChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val storeDir = context.filesDir.absolutePath
    val coroutineScope = rememberCoroutineScope()

    var isEditingName by remember { mutableStateOf(false) }
    var editNameValue by remember { mutableStateOf(deviceName) }

    // Dialog state flags
    var showColorPicker by remember { mutableStateOf(false) }
    var showEncryptionWhy by remember { mutableStateOf(false) }
    var showRevokeAllConfirm by remember { mutableStateOf(false) }
    var showTroubleshootSheet by remember { mutableStateOf(false) }
    var deviceToUnpair by remember { mutableStateOf<PairedDevice?>(null) }

    var pairedDevices by remember { mutableStateOf<List<PairedDevice>>(emptyList()) }

    fun refreshPairedDevices() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = NxfrService.NxfrBridge.nxfr_paired_list(storeDir)
                val json = JSONObject(jsonStr)
                if (json.has("devices")) {
                    val devicesArray = json.getJSONArray("devices")
                    val list = mutableListOf<PairedDevice>()
                    for (i in 0 until devicesArray.length()) {
                        val devObj = devicesArray.getJSONObject(i)
                        list.add(
                            PairedDevice(
                                deviceId = devObj.getString("device_id"),
                                name = devObj.getString("name"),
                                autoAccept = devObj.optString("auto_accept") == "always"
                            )
                        )
                    }
                    withContext(Dispatchers.Main) { pairedDevices = list }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshPairedDevices()
    }

    // Dialogs
    if (showColorPicker) {
        val currentSeed = ColorPreferenceHelper.getCustomSeedColor(context)
        ColorPickerDialog(
            initialColor = currentSeed,
            onDismiss = { showColorPicker = false },
            onColorSelected = { selectedColor ->
                ThemePreference.setCustomSeedColor(context, selectedColor.toArgb())
                ThemePreference.setColorMode(context, ThemePreference.COLOR_MODE_CUSTOM)
            }
        )
    }

    if (showEncryptionWhy) {
        AlertDialog(
            onDismissRequest = { showEncryptionWhy = false },
            icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            title = { Text("Encryption Invariant") },
            text = {
                Text(
                    "Why can't I disable encryption?\n\nBecause a transfer app's only job is to not leak your files. We removed the switch so nobody — not you, not your employer, not malware — can flip it."
                )
            },
            confirmButton = {
                TextButton(onClick = { showEncryptionWhy = false }) {
                    Text("Understood")
                }
            }
        )
    }

    if (showRevokeAllConfirm) {
        AlertDialog(
            onDismissRequest = { showRevokeAllConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Revoke All Paired Devices?") },
            text = { Text("All paired devices will be removed and will require manual approval on their next transfer.") },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            pairedDevices.forEach { dev ->
                                try { NxfrService.NxfrBridge.nxfr_unpair(storeDir, dev.deviceId) } catch (_: Exception) {}
                            }
                            withContext(Dispatchers.Main) {
                                pairedDevices = emptyList()
                                showRevokeAllConfirm = false
                                Toast.makeText(context, "All device pairings revoked", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Revoke All")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRevokeAllConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    deviceToUnpair?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToUnpair = null },
            title = { Text(stringResource(R.string.settings_unpair)) },
            text = { Text(stringResource(R.string.settings_unpair_confirm, device.name)) },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        NxfrService.NxfrBridge.nxfr_unpair(storeDir, device.deviceId)
                        withContext(Dispatchers.Main) {
                            refreshPairedDevices()
                            Toast.makeText(context, context.getString(R.string.settings_unpaired), Toast.LENGTH_SHORT).show()
                        }
                    }
                    deviceToUnpair = null
                }) {
                    Text(stringResource(R.string.settings_unpair))
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToUnpair = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showTroubleshootSheet) {
        TroubleshootSheet(onDismiss = { showTroubleshootSheet = false })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 1. IDENTITY SECTION ──────────────────────────────────────────────
        SettingsCard(title = "Identity") {
            if (isEditingName) {
                OutlinedTextField(
                    value = editNameValue,
                    onValueChange = { editNameValue = it },
                    label = { Text(stringResource(R.string.settings_device_name)) },
                    trailingIcon = {
                        IconButton(onClick = {
                            isEditingName = false
                            onDeviceNameChanged(editNameValue)
                            coroutineScope.launch(Dispatchers.IO) {
                                NxfrService.NxfrBridge.nxfr_set_name(storeDir, editNameValue)
                            }
                        }) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.settings_save))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_device_name), style = MaterialTheme.typography.bodySmall)
                        Text(deviceName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { isEditingName = true }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.settings_edit))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Device ID", style = MaterialTheme.typography.bodySmall)
                    Text(deviceId, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
                    Toast.makeText(context, "Device ID copied", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy ID")
                }
            }
        }

        // ── 2. GENERAL SECTION ───────────────────────────────────────────────
        SettingsCard(title = "General") {
            Text("Theme Mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            val themeMode by ThemePreference.themeMode.collectAsState()
            val themeSelection = when (themeMode) { ThemePreference.LIGHT -> 1; ThemePreference.DARK -> 2; else -> 0 }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf("System", "Light", "Dark")
                options.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = themeSelection == index,
                        onClick = {
                            val mode = when (index) { 1 -> ThemePreference.LIGHT; 2 -> ThemePreference.DARK; else -> ThemePreference.SYSTEM }
                            ThemePreference.setTheme(context, mode)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) {
                        Text(label)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Color Mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            val colorMode by ThemePreference.colorMode.collectAsState()
            val colorModeIndex = when (colorMode) {
                ThemePreference.COLOR_MODE_OLED -> 1
                ThemePreference.COLOR_MODE_CUSTOM -> 2
                else -> 0
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val colorOptions = listOf("Brand", "OLED Black", "Custom")
                colorOptions.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = colorModeIndex == index,
                        onClick = {
                            val selectedMode = when (index) {
                                1 -> ThemePreference.COLOR_MODE_OLED
                                2 -> {
                                    showColorPicker = true
                                    ThemePreference.COLOR_MODE_CUSTOM
                                }
                                else -> ThemePreference.COLOR_MODE_BRAND
                            }
                            ThemePreference.setColorMode(context, selectedMode)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = colorOptions.size)
                    ) {
                        Text(label)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            val appAnimationsEnabled by com.nxfr.android.ui.theme.AnimationPreference.animationsEnabled.collectAsState()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_animations), style = MaterialTheme.typography.bodyMedium)
                    if (com.nxfr.android.ui.theme.AnimationPreference.isSystemAnimationDisabled(context)) {
                        Text("Overridden: System animator scale is set to OFF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Switch(
                    checked = appAnimationsEnabled,
                    onCheckedChange = { com.nxfr.android.ui.theme.AnimationPreference.setAnimationsEnabled(context, it) }
                )
            }
        }

        // ── 3. RECEIVE SECTION ───────────────────────────────────────────────
        SettingsCard(title = "Receive") {
            val saveToGallery by NxfrPreferences.saveToGallery.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Save media to gallery", style = MaterialTheme.typography.bodyMedium)
                    Text("Automatically add received images/videos to Android Gallery", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = saveToGallery,
                    onCheckedChange = { NxfrPreferences.setSaveToGallery(context, it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            val saveToHistory by NxfrPreferences.saveToHistory.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Save to transfer history", style = MaterialTheme.typography.bodyMedium)
                    Text("History never leaves this device.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = saveToHistory,
                    onCheckedChange = { NxfrPreferences.setSaveToHistory(context, it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            val autoFinish by NxfrPreferences.autoFinish.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-finish completion sheet", style = MaterialTheme.typography.bodyMedium)
                    Text("Auto-dismiss sheet 1.5s after transfer completes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = autoFinish,
                    onCheckedChange = { NxfrPreferences.setAutoFinish(context, it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            val collisionRename by NxfrPreferences.collisionRename.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Collision rename", style = MaterialTheme.typography.bodyMedium)
                    Text("Rename duplicate incoming files as name (1).ext", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = collisionRename,
                    onCheckedChange = { NxfrPreferences.setCollisionRename(context, it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            val requirePin by NxfrPreferences.requirePin.collectAsState()
            OutlinedTextField(
                value = requirePin,
                onValueChange = { NxfrPreferences.setRequirePin(context, it) },
                label = { Text("Require PIN (Optional)") },
                placeholder = { Text("e.g. 1234") },
                supportingText = { Text("Optional PIN required for Web Upload link access.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── 4. SEND SECTION ──────────────────────────────────────────────────
        SettingsCard(title = "Send") {
            val defaultSendMode by NxfrPreferences.defaultSendMode.collectAsState()
            Text("Default Send Mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf("Single Recipient", "Multiple Recipients")
                modes.forEachIndexed { index, label ->
                    val modeKey = if (index == 0) "single" else "multiple"
                    SegmentedButton(
                        selected = defaultSendMode == modeKey,
                        onClick = { NxfrPreferences.setDefaultSendMode(context, modeKey) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                    ) {
                        Text(label)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            val showChecksum by NxfrPreferences.showChecksum.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show SHA-256 checksum on complete", style = MaterialTheme.typography.bodyMedium)
                    Text("Displays copyable SHA-256 hash chip in completion sheet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = showChecksum,
                    onCheckedChange = { NxfrPreferences.setShowChecksum(context, it) }
                )
            }
        }

        // ── 5. NETWORK SECTION ───────────────────────────────────────────────
        SettingsCard(title = "Network") {
            val advertiseMode by NxfrPreferences.advertiseMode.collectAsState()
            Text("Advertise On", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val advOptions = listOf("All Interfaces", "Wi-Fi Only")
                advOptions.forEachIndexed { index, label ->
                    val modeKey = if (index == 0) "all" else "wifi_only"
                    SegmentedButton(
                        selected = advertiseMode == modeKey,
                        onClick = { NxfrPreferences.setAdvertiseMode(context, modeKey) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = advOptions.size)
                    ) {
                        Text(label)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            val isListening by NxfrService.isListening.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("TCP Server Status", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (isListening) "Listening on port ${NxfrService.activePort}" else "Stopped", style = MaterialTheme.typography.bodySmall, color = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { NxfrService.startListening(context) },
                        enabled = !isListening
                    ) {
                        Text("Restart")
                    }
                    Button(
                        onClick = { NxfrService.stopListening(context) },
                        enabled = isListening,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Stop")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            val deviceModel by NxfrPreferences.deviceModel.collectAsState()
            OutlinedTextField(
                value = deviceModel,
                onValueChange = { NxfrPreferences.setDeviceModel(context, it) },
                label = { Text("Device Model Label") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
            var portText by remember { mutableStateOf(NxfrService.activePort.toString()) }
            var portError by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(
                value = portText,
                onValueChange = { input ->
                    portText = input
                    val p = input.toIntOrNull()
                    if (p != null && p in 1024..65535) {
                        portError = null
                        NxfrPreferences.setPort(context, p)
                        NxfrService.updateActivePortAndRebind(context, p)
                    } else {
                        portError = "Invalid port (1024–65535)"
                    }
                },
                label = { Text("Port") },
                supportingText = {
                    if (portError != null) Text(portError!!, color = MaterialTheme.colorScheme.error)
                    else Text("TCP port for transfers. Change only on conflict.")
                },
                isError = portError != null,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
            var discoveryTimeoutText by remember { mutableStateOf(NxfrService.discoveryTimeoutMs.toString()) }
            var timeoutError by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(
                value = discoveryTimeoutText,
                onValueChange = { input ->
                    discoveryTimeoutText = input
                    val t = input.toLongOrNull()
                    if (t != null && t in 500..15000) {
                        timeoutError = null
                        NxfrPreferences.setDiscoveryTimeoutMs(context, t)
                        NxfrService.discoveryTimeoutMs = t.toInt()
                    } else {
                        timeoutError = "Invalid timeout (500–15000 ms)"
                    }
                },
                label = { Text("Discovery Timeout (ms)") },
                supportingText = {
                    if (timeoutError != null) Text(timeoutError!!, color = MaterialTheme.colorScheme.error)
                    else Text("Wait time for nearby devices to answer (ms).")
                },
                isError = timeoutError != null,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
            var multicastAddressText by remember { mutableStateOf(NxfrService.multicastAddress) }
            var multicastError by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(
                value = multicastAddressText,
                onValueChange = { input ->
                    multicastAddressText = input
                    if (isValidMulticastIp(input)) {
                        multicastError = null
                        NxfrPreferences.setMulticastAddress(context, input)
                        NxfrService.updateMulticastAddressAndRebind(context, input)
                    } else {
                        multicastError = "Invalid multicast IP (224.0.0.0/4)"
                    }
                },
                label = { Text("Multicast Address") },
                supportingText = {
                    if (multicastError != null) Text(multicastError!!, color = MaterialTheme.colorScheme.error)
                    else Text("LAN beacon group address. Change only if your network blocks it.")
                },
                isError = multicastError != null,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    portText = "17394"
                    discoveryTimeoutText = "5000"
                    multicastAddressText = "224.0.0.251"
                    NxfrPreferences.resetNetworkDefaults(context)
                    NxfrService.discoveryTimeoutMs = 5000
                    NxfrService.updateMulticastAddressAndRebind(context, "224.0.0.251")
                    NxfrService.updateActivePortAndRebind(context, 17394)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset to defaults (17394 / 5000 / 224.0.0.251)")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                onClick = { showEncryptionWhy = true },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Always-on TLS 1.3 Encryption", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text("Encryption cannot be disabled by design. Tap for details.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ── 6. SECURITY SECTION ──────────────────────────────────────────────
        SettingsCard(title = "Security") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-accept Policy", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text("Managed via Receive tab visibility controls", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Paired Devices Manager", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            if (pairedDevices.isEmpty()) {
                Text("No paired devices yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                pairedDevices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text("ID: ${device.deviceId.take(8)}…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                        IconButton(onClick = { deviceToUnpair = device }) {
                            Icon(Icons.Default.Delete, contentDescription = "Unpair", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://nxfr.github.io/nxfr/security/tofu/"))
                        try { context.startActivity(intent) } catch (_: Exception) {}
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Trust-On-First-Use (TOFU) Explainer", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showRevokeAllConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Revoke All Paired Devices")
            }
        }

        // ── 7. ABOUT & DIAGNOSTICS SECTION ───────────────────────────────────
        SettingsCard(title = "Troubleshoot & About") {
            OutlinedButton(
                onClick = { showTroubleshootSheet = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Run On-Device Diagnostics")
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { DebugBundleExporter.exportDebugBundle(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export Debug Bundle")
            }
            Text("No file contents, no keys, no tokens.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))

            Spacer(modifier = Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NXFR Protocol", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                Text("v0.2.8-alpha (15)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("No cloud. No account. Just math.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

            Spacer(modifier = Modifier.height(12.dp))
            val links = listOf(
                "Documentation" to "https://nxfr.github.io/nxfr/",
                "GitHub Repository" to "https://github.com/nxfr/nxfr",
                "License (MIT / Apache-2.0)" to "https://github.com/nxfr/nxfr/blob/main/LICENSE",
                "Privacy Policy" to "https://nxfr.github.io/nxfr/security/privacy/",
                "Security Model" to "https://nxfr.github.io/nxfr/security/"
            )
            links.forEach { (label, url) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                        }
                )
            }
        }
    }
}

data class PairedDevice(val deviceId: String, val name: String, val autoAccept: Boolean)

private object ColorPreferenceHelper {
    fun getCustomSeedColor(context: Context): androidx.compose.ui.graphics.Color {
        val prefs = context.getSharedPreferences("nxfr_prefs", Context.MODE_PRIVATE)
        val argb = prefs.getInt("custom_seed_color", 0xFF00E5FF.toInt())
        return androidx.compose.ui.graphics.Color(argb)
    }
}

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

private fun isValidMulticastIp(ip: String): Boolean {
    val parts = ip.split(".")
    if (parts.size != 4) return false
    val first = parts[0].toIntOrNull() ?: return false
    if (first !in 224..239) return false
    return parts.drop(1).all {
        val num = it.toIntOrNull()
        num != null && num in 0..255
    }
}
