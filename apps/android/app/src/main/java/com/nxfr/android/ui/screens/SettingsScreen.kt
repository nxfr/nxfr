package com.nxfr.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.ui.unit.dp
import com.nxfr.android.R
import com.nxfr.android.service.NxfrService

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
    var themeSelection by remember { mutableStateOf(0) } // 0: System, 1: Light, 2: Dark
    var animationsEnabled by remember { mutableStateOf(true) }
    
    val context = LocalContext.current

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
                        onClick = { themeSelection = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) {
                        Text(label)
                    }
                }
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
                val labels = listOf("Phone", "Tablet", "Laptop")
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
                    val clip = ClipData.newPlainText("Device ID", deviceId)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, context.getString(R.string.settings_device_id_copied), Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.settings_copy))
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
                var discoveryTimeout by remember { mutableStateOf("5000") }
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

        // Security Section
        SettingsCard(title = stringResource(R.string.settings_section_security)) {
            Text(stringResource(R.string.settings_paired_empty), style = MaterialTheme.typography.bodyMedium)
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
            Text(stringResource(R.string.settings_changelog))
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.settings_privacy_policy))
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.settings_support_github))
        }
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
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}
