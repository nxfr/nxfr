package com.nxfr.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.ui.theme.deckColors
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
fun IdentityDeckBar(
    deviceName: String,
    deviceId: String,
    onDeviceNameChanged: (String) -> Unit,
    onShowInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors
    val context = LocalContext.current
    var showRenameDialog by remember { mutableStateOf(false) }
    var editNameValue by remember { mutableStateOf(deviceName) }

    val shortId = if (deviceId.length >= 4) deviceId.substring(0, 4) else deviceId
    val deviceIp = remember { getPrimaryIpAddress() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(deck.surface, RoundedCornerShape(4.dp))
            .border(1.dp, deck.gridLine, RoundedCornerShape(4.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Monogram Badge
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(deck.signalBeam, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "N",
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                color = deck.rootBackground
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Device Alias & Hex Tag
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable {
                    editNameValue = deviceName
                    showRenameDialog = true
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = deck.textPrimary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "#$shortId",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = deck.textSecondary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Rename",
                    modifier = Modifier.size(14.dp),
                    tint = deck.textDim
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = deviceIp,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = deck.signalBeam
            )
        }

        // Info / Details Button
        IconButton(
            onClick = onShowInfo,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "Device Information",
                tint = deck.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Device", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = editNameValue,
                    onValueChange = { editNameValue = it },
                    label = { Text("Device Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editNameValue.isNotBlank()) {
                        onDeviceNameChanged(editNameValue)
                    }
                    showRenameDialog = false
                }) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun getPrimaryIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: ""
                    if (!host.startsWith("127.")) {
                        return host
                    }
                }
            }
        }
    } catch (_: Throwable) {}
    return "127.0.0.1"
}
