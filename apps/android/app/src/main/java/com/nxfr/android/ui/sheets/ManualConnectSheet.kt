package com.nxfr.android.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.transfer.AddressParser
import com.nxfr.android.transfer.RecentNodesRepository
import com.nxfr.android.ui.theme.deckColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualConnectSheet(
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val deck = MaterialTheme.deckColors
    val haptics = LocalHapticFeedback.current

    var addressInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var recentNodes by remember { mutableStateOf(RecentNodesRepository.getRecentNodes(context)) }

    fun submitAddress() {
        val parsed = AddressParser.parse(addressInput)
        if (parsed == null) {
            errorMessage = "INVALID ADDRESS FORMAT — Expected IP[:port] or hostname[:port]"
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        } else {
            errorMessage = null
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            RecentNodesRepository.addRecentNode(context, parsed.formatted)
            onConnect(parsed.formatted)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = deck.surface,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(deck.gridLineBright, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.GpsFixed,
                        contentDescription = null,
                        tint = deck.signalBeam,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "MANUAL NODE CONNECTION",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.textPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
                        .border(0.5.dp, deck.signalBeam, RoundedCornerShape(2.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "[TLS 1.3 / TCP 17394]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.signalBeam
                    )
                }
            }

            Text(
                text = "Enter the target station's IP address or LAN hostname to initiate a direct cryptographic handshake.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = deck.textSecondary
            )

            // 2. Recent Target Nodes (if any)
            if (recentNodes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "RECENT TARGET NODES:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.textDim
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        recentNodes.forEach { node ->
                            Box(
                                modifier = Modifier
                                    .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
                                    .border(0.5.dp, deck.gridLineBright, RoundedCornerShape(2.dp))
                                    .clickable(role = Role.Button) {
                                        addressInput = node
                                        errorMessage = null
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "⌖ $node",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = deck.signalBeam
                                )
                            }
                        }
                    }
                }
            }

            // 3. Monospace Input Field
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = addressInput,
                    onValueChange = {
                        addressInput = it
                        if (errorMessage != null) errorMessage = null
                    },
                    label = {
                        Text(
                            text = "TARGET ADDRESS (IPv4 / IPv6 / HOSTNAME)",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    },
                    placeholder = {
                        Text(
                            text = "192.168.1.104[:17394]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = deck.textDim
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = deck.textPrimary
                    ),
                    isError = errorMessage != null,
                    trailingIcon = {
                        if (addressInput.isNotEmpty()) {
                            IconButton(onClick = { addressInput = "" }) {
                                Icon(Icons.Outlined.Clear, contentDescription = "Clear address", tint = deck.textSecondary)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { submitAddress() }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp)
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = deck.signalAlert,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 4. Action Buttons
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { submitAddress() },
                    shape = RoundedCornerShape(2.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = deck.signalBeam,
                        contentColor = deck.rootBackground
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "CONNECT & TRANSMIT →",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(
                        text = "CANCEL",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = deck.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
