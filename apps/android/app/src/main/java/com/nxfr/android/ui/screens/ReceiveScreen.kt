package com.nxfr.android.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    deviceName: String = "My Device",
    deviceId: String = "abcd1234efgh5678",
    onDeviceNameChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var isVisible by remember { mutableStateOf(false) }
    var autoAcceptState by remember { mutableIntStateOf(0) } // 0: Off, 1: Paired, 2: Everyone
    var showRenameDialog by remember { mutableStateOf(false) }
    var newDeviceName by remember { mutableStateOf(deviceName) }
    var showWarningDialog by remember { mutableStateOf(false) }
    var saveFolderPath by remember { mutableStateOf<Uri?>(null) }
    
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> saveFolderPath = uri }
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        
        // 1. Centered NXFR logo with infinite radar-pulse animation
        val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse
            ),
            label = "AlphaPulse"
        )
        val scale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse
            ),
            label = "ScalePulse"
        )

        Box(
            contentAlignment = Alignment.Center, 
            modifier = Modifier.padding(top = 32.dp, bottom = 16.dp)
        ) {
            // Outer ring
            Surface(
                modifier = Modifier
                    .size(140.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {}
            
            // Inner logo
            Surface(
                modifier = Modifier.size(100.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "N",
                        fontSize = 72.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 2. Device alias + short ID
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { 
                newDeviceName = deviceName
                showRenameDialog = true 
            }
        ) {
            val shortId = if (deviceId.length >= 4) deviceId.substring(0, 4) else deviceId
            Text(
                text = "$deviceName #$shortId",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.receive_rename_device),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text(stringResource(R.string.receive_rename_device)) },
                text = {
                    OutlinedTextField(
                        value = newDeviceName,
                        onValueChange = { newDeviceName = it },
                        label = { Text(stringResource(R.string.receive_device_name_label)) }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { 
                        onDeviceNameChanged(newDeviceName)
                        showRenameDialog = false 
                    }) {
                        Text(stringResource(R.string.receive_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text(stringResource(R.string.receive_cancel))
                    }
                }
            )
        }

        // 3. Visibility toggle
        ElevatedCard(
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.receive_visibility_toggle),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.receive_visibility_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isVisible,
                    onCheckedChange = { isVisible = it },
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }

        // 4. Auto-accept segmented buttons
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.receive_auto_accept_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    stringResource(R.string.receive_auto_accept_off),
                    stringResource(R.string.receive_auto_accept_paired_phase8),
                    stringResource(R.string.receive_auto_accept_everyone)
                )
                options.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = autoAcceptState == index,
                        onClick = { 
                            if (index == 2 && autoAcceptState != 2) {
                                showWarningDialog = true
                            } else {
                                autoAcceptState = index 
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        enabled = index != 1 // Paired only is DISABLED
                    ) {
                        Text(label)
                    }
                }
            }
        }

        if (showWarningDialog) {
            AlertDialog(
                onDismissRequest = { showWarningDialog = false },
                title = { Text(stringResource(R.string.receive_warning)) },
                text = { Text(stringResource(R.string.receive_auto_accept_everyone_warning)) },
                confirmButton = {
                    TextButton(onClick = { 
                        autoAcceptState = 2
                        showWarningDialog = false 
                    }) {
                        Text(stringResource(R.string.receive_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWarningDialog = false }) {
                        Text(stringResource(R.string.receive_cancel))
                    }
                }
            )
        }

        // 5. Save-to folder row
        ElevatedCard(
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { folderPicker.launch(null) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = stringResource(R.string.receive_folder_icon),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.receive_save_to),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = saveFolderPath?.toString() ?: stringResource(R.string.receive_default_folder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { folderPicker.launch(null) }) {
                    Text(stringResource(R.string.receive_change_folder))
                }
            }
        }

        // 6. Active transfers section
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.receive_active_transfers),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            ElevatedCard(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.receive_no_active),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // Add bottom padding for better scroll feel
        Spacer(modifier = Modifier.height(16.dp))
    }
}
