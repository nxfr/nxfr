package com.nxfr.android.ui.sheets

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nxfr.android.discovery.ClientJoinState
import com.nxfr.android.discovery.P2pPeer
import com.nxfr.android.discovery.P2pState
import com.nxfr.android.discovery.SoftApState
import com.nxfr.android.service.NxfrService
import com.nxfr.android.ui.theme.deckColors
import kotlinx.coroutines.flow.MutableStateFlow

private fun hasPermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesertSheet(
    onDismiss: () -> Unit,
    onConnectToNode: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val p2pState by (NxfrService.p2pManager?.state ?: MutableStateFlow(P2pState.Idle)).collectAsState()
    val softApState by (NxfrService.softApManager?.hostState ?: MutableStateFlow(SoftApState.Idle)).collectAsState()
    val clientJoinState by (NxfrService.softApManager?.clientState ?: MutableStateFlow(ClientJoinState.Idle)).collectAsState()

    var permissionGranted by remember { mutableStateOf(hasPermission(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }

    ModalBottomSheet(
        onDismissRequest = {
            val currentState = NxfrService.p2pManager?.state?.value
            if (currentState is P2pState.Discovering || currentState is P2pState.PeersFound) {
                NxfrService.p2pManager?.cancelDiscoveryOnly()
            }
            onDismiss()
        },
        modifier = modifier,
        containerColor = deck.surface,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(deck.gridLineBright, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp)
        ) {
            // 1. Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.WifiTethering,
                    contentDescription = null,
                    tint = deck.signalBeam,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "DESERT MODE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = deck.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "[OFF-GRID]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = deck.signalBeam
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Description
            Text(
                text = "Direct peer-to-peer connection without internet or LAN.\nBoth devices must be nearby with Wi-Fi radio on.",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = deck.textSecondary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 3. Permission Gate
            if (!permissionGranted) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                    border = BorderStroke(1.dp, deck.gridLine),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Wi-Fi peer permission required for Desert mode",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = deck.signalWarning
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                                } else {
                                    permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = deck.signalBeam,
                                contentColor = deck.rootBackground
                            ),
                            shape = RoundedCornerShape(2.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text(
                                text = "[GRANT PERMISSION]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                // 4. Status Row (when P2pState.Ready or SoftApState.Active)
                if (p2pState is P2pState.Ready || softApState is SoftApState.Active) {
                    val statusText = if (p2pState is P2pState.Ready) {
                        val ready = p2pState as P2pState.Ready
                        "📡 DIRECT LINK · ${if (ready.isGO) "GO" else "CLIENT"} · p2p0 / ${ready.goIp}"
                    } else {
                        "📡 HOTSPOT ACTIVE · HOST"
                    }
                    
                    Card(
                        colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                        border = BorderStroke(1.dp, deck.signalBeam),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = statusText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = deck.signalBeam
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    NxfrService.p2pManager?.teardown()
                                    NxfrService.softApManager?.teardown()
                                    NxfrService.setDesertSessionActive(false)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = deck.signalAlert
                                ),
                                border = BorderStroke(1.dp, deck.signalAlert),
                                shape = RoundedCornerShape(2.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text(
                                    text = "[END DIRECT LINK]",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 5. Discover Button (only when Idle or Failed, AND permissionGranted)
                if (p2pState is P2pState.Idle || p2pState is P2pState.Failed) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val aid = NxfrService.deviceId.value.let { did ->
                                if (did.isNotEmpty()) {
                                    try {
                                        val dateStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                                        NxfrService.NxfrBridge.nxfr_advertised_id(did, dateStr)
                                    } catch (_: Throwable) { did.take(8) }
                                } else "unknown"
                            }
                            NxfrService.setDesertSessionActive(true)
                            NxfrService.p2pManager?.startDiscovery(aid, NxfrService.deviceName.value)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = deck.signalBeam,
                            contentColor = deck.rootBackground
                        ),
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "[📡 DISCOVER NEARBY STATIONS]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 6. Scanning Indicator (when Discovering)
                if (p2pState is P2pState.Discovering) {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(
                            color = deck.signalBeam,
                            trackColor = deck.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Scanning for nearby stations...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = deck.textPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 7. Peer List (when PeersFound)
                if (p2pState is P2pState.PeersFound) {
                    val peers = (p2pState as P2pState.PeersFound).peers
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        items(peers) { peer ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                                border = BorderStroke(1.dp, deck.gridLine),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = peer.deviceName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = deck.textPrimary
                                        )
                                        Text(
                                            text = "#" + peer.deviceName.take(4), // Approximation since AID might not be parsed
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = deck.textSecondary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = peer.deviceAddress,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = deck.signalBeam
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            NxfrService.p2pManager?.connect(peer)
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = deck.signalBeam,
                                            contentColor = deck.rootBackground
                                        ),
                                        shape = RoundedCornerShape(2.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                    ) {
                                        Text(
                                            text = "[CONNECT →]",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 8. Forming Indicator (when Forming)
                if (p2pState is P2pState.Forming) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = deck.signalBeam,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Forming direct link...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = deck.textPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 9. Ready Auto-Connect (when Ready and NOT isGO)
                LaunchedEffect(p2pState) {
                    if (p2pState is P2pState.Ready) {
                        val readyState = p2pState as P2pState.Ready
                        if (!readyState.isGO) {
                            onConnectToNode("${readyState.goIp}:17394")
                        }
                    }
                }

                // 10. GO Waiting (when Ready and IS GO)
                if (p2pState is P2pState.Ready && (p2pState as P2pState.Ready).isGO) {
                    Text(
                        text = "You are the group owner. Waiting for client to connect...\nEnsure your visibility breaker is ON so the client can find you.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = deck.textPrimary
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    LaunchedEffect(p2pState) {
                        if (!NxfrService.isListening.value) {
                            val intent = Intent(context, NxfrService::class.java)
                            context.startService(intent)
                            NxfrService.startListening(context)
                        }
                    }
                }
            }

            HorizontalDivider(color = deck.gridLine, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // 11. SoftAP Fallback Card
            Text(
                text = "AUTONOMOUS HOTSPOT FALLBACK",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = deck.textDim
            )
            Spacer(modifier = Modifier.height(8.dp))

            when (softApState) {
                is SoftApState.Idle -> {
                    Text(
                        text = "If Wi-Fi Direct fails, create an autonomous hotspot for the other device to join.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = deck.textSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    var showJoinForm by remember { mutableStateOf(false) }

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                NxfrService.softApManager?.startHotspot()
                                NxfrService.setDesertSessionActive(true)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = deck.signalBeam,
                                contentColor = deck.rootBackground
                            ),
                            shape = RoundedCornerShape(2.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text(
                                text = "[START HOTSPOT]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showJoinForm = !showJoinForm
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = deck.signalBeam
                            ),
                            border = BorderStroke(1.dp, deck.signalBeam),
                            shape = RoundedCornerShape(2.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text(
                                text = "[JOIN HOTSPOT]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 12. Client Join Form
                    if (showJoinForm) {
                        Spacer(modifier = Modifier.height(16.dp))
                        var joinSsid by remember { mutableStateOf("") }
                        var joinPassphrase by remember { mutableStateOf("") }

                        OutlinedTextField(
                            value = joinSsid,
                            onValueChange = { joinSsid = it },
                            label = { Text("SSID", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = deck.signalBeam,
                                unfocusedBorderColor = deck.gridLine,
                                focusedTextColor = deck.textPrimary,
                                unfocusedTextColor = deck.textPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = joinPassphrase,
                            onValueChange = { joinPassphrase = it },
                            label = { Text("Passphrase", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = deck.signalBeam,
                                unfocusedBorderColor = deck.gridLine,
                                focusedTextColor = deck.textPrimary,
                                unfocusedTextColor = deck.textPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                NxfrService.softApManager?.joinNetwork(joinSsid, joinPassphrase)
                                NxfrService.setDesertSessionActive(true)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = deck.signalBeam,
                                contentColor = deck.rootBackground
                            ),
                            shape = RoundedCornerShape(2.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text(
                                text = "[CONNECT]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                is SoftApState.Active -> {
                    val activeState = softApState as SoftApState.Active
                    Card(
                        colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                        border = BorderStroke(1.dp, deck.gridLine),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "SSID        : ${activeState.ssid}\n" +
                                       "PASSPHRASE  : ${activeState.passphrase ?: "Open system hotspot settings to view password"}\n" +
                                       "HOST IP     : ${activeState.hostIp}\n" +
                                       "PORT        : 17394",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = deck.signalBeam,
                                lineHeight = 16.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            NxfrService.softApManager?.stopHotspot()
                            NxfrService.setDesertSessionActive(false)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = deck.signalAlert
                        ),
                        border = BorderStroke(1.dp, deck.signalAlert),
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "[STOP HOTSPOT]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                is SoftApState.Starting -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = deck.signalBeam,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Starting hotspot...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = deck.textPrimary
                        )
                    }
                }
                is SoftApState.Failed -> {
                    Text(
                        text = "Failed: ${(softApState as SoftApState.Failed).reason}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = deck.signalAlert
                    )
                }
            }

            // Client Join State Observation
            when (clientJoinState) {
                is ClientJoinState.Connecting -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Connecting to network...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = deck.textPrimary
                    )
                }
                is ClientJoinState.Connected -> {
                    val connectedState = clientJoinState as ClientJoinState.Connected
                    LaunchedEffect(clientJoinState) {
                        onConnectToNode("${connectedState.hostIp}:17394")
                    }
                }
                is ClientJoinState.Failed -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Failed to join: ${(clientJoinState as ClientJoinState.Failed).reason}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = deck.signalAlert
                    )
                }
                else -> {}
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
