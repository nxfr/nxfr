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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_animations), modifier = Modifier.weight(1f))
                Switch(
                    checked = animationsEnabled,
                    onCheckedChange = { animationsEnabled = it }
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
            if (pairedDevices.isEmpty()) {
                Text(stringResource(R.string.settings_no_paired_devices), style = MaterialTheme.typography.bodyMedium)
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
                var port by remember { mutableStateOf(NxfrService.DEFAULT_PORT.toString()) }
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.settings_port)) },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                var discoveryTimeout by remember { mutableStateOf(context.getString(R.string.settings_discovery_timeout_default)) }
                OutlinedTextField(
                    value = discoveryTimeout,
                    onValueChange = { discoveryTimeout = it },
                    label = { Text(stringResource(R.string.settings_discovery_timeout)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                var multicastAddress by remember { mutableStateOf(NxfrService.DEFAULT_MULTICAST) }
                OutlinedTextField(
                    value = multicastAddress,
                    onValueChange = { multicastAddress = it },
                    label = { Text(stringResource(R.string.settings_multicast_address)) },
                    modifier = Modifier.fillMaxWidth()
                )

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
