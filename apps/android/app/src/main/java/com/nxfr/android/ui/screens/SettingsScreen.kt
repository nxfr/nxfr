package com.nxfr.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.nxfr.android.service.NxfrService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.nxfr.android.ui.theme.ThemePreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    deviceName: String,
    deviceId: String,
    onDeviceNameChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var isEditingName by remember { mutableStateOf(false) }
    var editNameValue by remember { mutableStateOf(deviceName) }
    val themeMode by ThemePreference.themeMode.collectAsState()
    val themeSelection = when (themeMode) { ThemePreference.LIGHT -> 1; ThemePreference.DARK -> 2; else -> 0 }
    var animationsEnabled by remember { mutableStateOf(true) }
    
    val context = LocalContext.current
    val storeDir = context.filesDir.absolutePath
    val coroutineScope = rememberCoroutineScope()
    
    var pairedDevices by remember { mutableStateOf<List<PairedDevice>>(emptyList()) }
    var deviceToUnpair by remember { mutableStateOf<PairedDevice?>(null) }
    
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
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
                    pairedDevices = list
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
                        val updatedList = pairedDevices.filter { it.deviceId != device.deviceId }
                        withContext(Dispatchers.Main) {
                            pairedDevices = updatedList
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // General Section
        SettingsCard(title = stringResource(R.string.settings_section_general)) {
            Text(stringResource(R.string.settings_theme))
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    stringResource(R.string.settings_theme_system),
                    stringResource(R.string.settings_theme_light),
                    stringResource(R.string.settings_theme_dark)
                )
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
            val useDynamicColor by ThemePreference.useDynamicColor.collectAsState()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Material You Dynamic Color", style = MaterialTheme.typography.bodyMedium)
                    Text("Adapt palette to system wallpaper (Default: Bold Identity)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = useDynamicColor,
                    onCheckedChange = { ThemePreference.setDynamicColor(context, it) }
                )
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

        // Identity Section
        SettingsCard(title = stringResource(R.string.settings_section_identity)) {
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
                        Text(deviceName, style = MaterialTheme.typography.bodyLarge)
                    }
                    IconButton(onClick = { isEditingName = true }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.settings_edit))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.settings_device_type), style = MaterialTheme.typography.bodySmall)
            var deviceType by remember { mutableStateOf(0) }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val types = listOf(Icons.Default.Phone, Icons.Default.Tablet, Icons.Default.Laptop)
                val labels = listOf(
                    stringResource(R.string.settings_device_type_phone),
                    stringResource(R.string.settings_device_type_tablet),
                    stringResource(R.string.settings_device_type_laptop)
                )
                types.forEachIndexed { index, icon ->
                    SegmentedButton(
                        selected = deviceType == index,
                        onClick = { deviceType = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size)
                    ) {
                        Icon(icon, contentDescription = labels[index])
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_device_id), style = MaterialTheme.typography.bodySmall)
                    Text(deviceId, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(context.getString(R.string.settings_clipboard_label), deviceId)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, context.getString(R.string.settings_device_id_copied), Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.settings_copy))
                }
            }
        }

        // Paired Devices Section
        SettingsCard(title = stringResource(R.string.settings_paired_devices)) {
            Text(
                text = "These devices skip consent under 'Paired'. Unpair any device you no longer trust.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (pairedDevices.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.QrCode,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "No paired devices yet",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Scan a device's QR in the Send tab, or tick 'pair' when accepting a transfer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                pairedDevices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${device.name} #${device.deviceId.take(4)}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(if (device.autoAccept) R.string.settings_pair_auto_accept_on else R.string.settings_pair_auto_accept_off),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = device.autoAccept,
                            onCheckedChange = { checked ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    val policy = if (checked) "always" else "prompt"
                                    NxfrService.NxfrBridge.nxfr_set_auto_accept(storeDir, device.deviceId, policy)
                                    val updatedList = pairedDevices.map { 
                                        if (it.deviceId == device.deviceId) it.copy(autoAccept = checked) else it 
                                    }
                                    withContext(Dispatchers.Main) {
                                        pairedDevices = updatedList
                                    }
                                }
                            }
                        )
                        IconButton(onClick = { deviceToUnpair = device }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_unpair))
                        }
                    }
                }
            }
        }

        // Battery & Background Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Battery & background",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "NXFR listens only while you choose to be visible.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                val fallbackIntent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(fallbackIntent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Battery settings not available", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.BatterySaver, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disable battery optimization")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Xiaomi/Samsung/OPPO note: Enable 'Autostart' or 'No restrictions' if background transfers pause when screen turns off.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }

        // Advanced Settings Toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_advanced), modifier = Modifier.weight(1f))
            Switch(
                checked = showAdvancedSettings,
                onCheckedChange = { showAdvancedSettings = it }
            )
        }

        // Network Section
        if (showAdvancedSettings) {
            SettingsCard(title = stringResource(R.string.settings_section_network)) {
                var portText by remember { mutableStateOf(NxfrService.activePort.toString()) }
                var portError by remember { mutableStateOf<String?>(null) }

                OutlinedTextField(
                    value = portText,
                    onValueChange = { input ->
                        portText = input
                        val p = input.toIntOrNull()
                        if (p != null && p in 1024..65535) {
                            portError = null
                            NxfrService.updateActivePortAndRebind(context, p)
                        } else {
                            portError = "Invalid port (1024–65535)"
                        }
                    },
                    label = { Text(stringResource(R.string.settings_port)) },
                    supportingText = {
                        if (portError != null) {
                            Text(portError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("TCP port for transfers. Change only on conflict.")
                        }
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
                        val t = input.toIntOrNull()
                        if (t != null && t in 500..15000) {
                            timeoutError = null
                            NxfrService.discoveryTimeoutMs = t
                        } else {
                            timeoutError = "Invalid timeout (500–15000 ms)"
                        }
                    },
                    label = { Text(stringResource(R.string.settings_discovery_timeout)) },
                    supportingText = {
                        if (timeoutError != null) {
                            Text(timeoutError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("Wait time for nearby devices to answer (ms).")
                        }
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
                            NxfrService.updateMulticastAddressAndRebind(context, input)
                        } else {
                            multicastError = "Invalid multicast IP (224.0.0.0/4)"
                        }
                    },
                    label = { Text(stringResource(R.string.settings_multicast_address)) },
                    supportingText = {
                        if (multicastError != null) {
                            Text(multicastError!!, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("LAN beacon group address. Change only if your network blocks it.")
                        }
                    },
                    isError = multicastError != null,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        portText = NxfrService.DEFAULT_PORT.toString()
                        portError = null
                        discoveryTimeoutText = "5000"
                        timeoutError = null
                        multicastAddressText = NxfrService.DEFAULT_MULTICAST
                        multicastError = null

                        NxfrService.discoveryTimeoutMs = 5000
                        NxfrService.updateMulticastAddressAndRebind(context, NxfrService.DEFAULT_MULTICAST)
                        NxfrService.updateActivePortAndRebind(context, NxfrService.DEFAULT_PORT)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset to defaults (17394 / 5000 / 224.0.0.251)")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.settings_encryption))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_encryption_enabled))
                }
            }
        }

        // Other Section
        SettingsCard(title = stringResource(R.string.settings_section_other)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.app_version), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.settings_changelog),
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nxfr/nxfr/releases"))
                    context.startActivity(intent)
                },
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_privacy_policy),
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://nxfr.github.io/nxfr/security/privacy/"))
                    context.startActivity(intent)
                },
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_support_github),
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nxfr/nxfr/issues"))
                    context.startActivity(intent)
                },
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

data class PairedDevice(val deviceId: String, val name: String, val autoAccept: Boolean)

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
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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
