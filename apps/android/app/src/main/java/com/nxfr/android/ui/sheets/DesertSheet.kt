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
import kotlinx.coroutines.delay
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
    val boundIface by (NxfrService.p2pManager?.boundIface ?: MutableStateFlow<String?>(null)).collectAsState()
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
                // 4. Status Row (when P2pState.Ready, SoftApState.Active, or ClientJoinState.Connected)
                if (p2pState is P2pState.Ready || softApState is SoftApState.Active || clientJoinState is ClientJoinState.Connected) {
                    val statusText = if (p2pState is P2pState.Ready) {
                        val ready = p2pState as P2pState.Ready
                        val routed = boundIface ?: ready.iface
                        "📡 DIRECT LINK · ${if (ready.isGO) "GO" else "CLIENT"} · ${ready.goIp}\nROUTED: $routed"
                    } else if (clientJoinState is ClientJoinState.Connected) {
                        val connected = clientJoinState as ClientJoinState.Connected
                        "📡 HOTSPOT LINK · CLIENT · ${connected.hostIp}\nROUTED: softap"
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
                                    android.util.Log.i("DesertSheet", "User tapped [END DIRECT LINK] — tearing down P2P/SoftAP and restoring default routing")
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

                // 5. Discover Button (only when Idle)
                if (p2pState is P2pState.Idle) {
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
                    Card(
                        colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                        border = BorderStroke(1.dp, deck.signalBeam),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            LinearProgressIndicator(
                                color = deck.signalBeam,
                                trackColor = deck.surfaceContainer,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Scanning for nearby stations (Wi-Fi Direct)...",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = deck.textPrimary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    NxfrService.p2pManager?.cancelDiscoveryOnly()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = deck.signalAlert),
                                border = BorderStroke(1.dp, deck.signalAlert),
                                shape = RoundedCornerShape(2.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                            ) {
                                Text(
                                    text = "[CANCEL SCAN]",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 6b. Failure Banner (when Failed)
                if (p2pState is P2pState.Failed) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                        border = BorderStroke(1.dp, deck.signalAlert),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "⚠️ STATION DISCOVERY FAILED\n${(p2pState as P2pState.Failed).reason}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = deck.signalAlert,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
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
                                        .weight(1f)
                                        .height(40.dp)
                                ) {
                                    Text(
                                        text = "[RETRY SCAN]",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        NxfrService.p2pManager?.cancelDiscoveryOnly()
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = deck.textSecondary),
                                    border = BorderStroke(1.dp, deck.gridLine),
                                    shape = RoundedCornerShape(2.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                ) {
                                    Text(
                                        text = "[DISMISS]",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 7. Peer List (when PeersFound)
                if (p2pState is P2pState.PeersFound) {
                    val peers = (p2pState as P2pState.PeersFound).peers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DISCOVERED STATIONS (${peers.size})",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = deck.signalBeam
                        )
                        TextButton(
                            onClick = {
                                val aid = NxfrService.deviceId.value.let { did ->
                                    if (did.isNotEmpty()) {
                                        try {
                                            val dateStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                                            NxfrService.NxfrBridge.nxfr_advertised_id(did, dateStr)
                                        } catch (_: Throwable) { did.take(8) }
                                    } else "unknown"
                                }
                                NxfrService.p2pManager?.startDiscovery(aid, NxfrService.deviceName.value)
                            }
                        ) {
                            Text(
                                text = "RE-SCAN",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = deck.signalBeam
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
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
                                            text = "#" + peer.deviceAddress.takeLast(4),
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
                            val iface = boundIface ?: readyState.iface
                            android.util.Log.i("DesertSheet", "Client ready on routed interface $iface — settling 300ms before connecting to GO at ${readyState.goIp}:17394...")
                            delay(300) // Allow GO listener socket and process network binding to stabilize
                            android.util.Log.i("DesertSheet", "Auto-connecting to GO at ${readyState.goIp}:17394")
                            onConnectToNode("${readyState.goIp}:17394")
                        }
                    }
                }

                // 10. GO Waiting (when Ready and IS GO)
                if (p2pState is P2pState.Ready && (p2pState as P2pState.Ready).isGO) {
                    Text(
                        text = "You are the group owner. Waiting for client to connect...\nListener active on TCP port 17394.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = deck.textPrimary
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    LaunchedEffect(p2pState) {
                        if (!NxfrService.isListening.value) {
                            android.util.Log.i("DesertSheet", "GO ensuring TCP 17394 listener is active...")
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
                    var hotspotTab by remember { mutableStateOf(0) } // 0: Host, 1: Join

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { hotspotTab = 0 },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hotspotTab == 0) deck.signalBeam else deck.surfaceVariant,
                                contentColor = if (hotspotTab == 0) deck.rootBackground else deck.textPrimary
                            ),
                            shape = RoundedCornerShape(2.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Text(
                                text = "HOST HOTSPOT",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = { hotspotTab = 1 },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hotspotTab == 1) deck.signalBeam else deck.surfaceVariant,
                                contentColor = if (hotspotTab == 1) deck.rootBackground else deck.textPrimary
                            ),
                            shape = RoundedCornerShape(2.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Text(
                                text = "JOIN HOTSPOT",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (hotspotTab == 0) {
                        Text(
                            text = "Start a local Wi-Fi hotspot on this phone. Other devices can join to exchange files over direct TCP/TLS.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = deck.textSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
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
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text(
                                text = "[⚡ START LOCAL HOTSPOT]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        var joinSsid by remember { mutableStateOf("") }
                        var joinPassphrase by remember { mutableStateOf("") }

                        OutlinedTextField(
                            value = joinSsid,
                            onValueChange = { joinSsid = it },
                            label = { Text("Hotspot SSID", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
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
                            label = { Text("Passphrase (optional for open)", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
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
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                NxfrService.softApManager?.joinNetwork(joinSsid, joinPassphrase.ifEmpty { null })
                                NxfrService.setDesertSessionActive(true)
                            },
                            enabled = joinSsid.isNotBlank(),
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
                                text = "[JOIN & CONNECT]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                is SoftApState.Active -> {
                    val activeState = softApState as SoftApState.Active
                    
                    LaunchedEffect(softApState) {
                        if (!NxfrService.isListening.value) {
                            val intent = Intent(context, NxfrService::class.java)
                            context.startService(intent)
                            NxfrService.startListening(context)
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                        border = BorderStroke(1.dp, deck.signalBeam),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "⚡ HOTSPOT RUNNING (HOST)\n" +
                                       "SSID        : ${activeState.ssid}\n" +
                                       "PASSPHRASE  : ${activeState.passphrase ?: "Check system hotspot settings"}\n" +
                                       "HOST IP     : ${activeState.hostIp}\n" +
                                       "PORT        : 17394 (LISTENING)",
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
                            text = "Starting hotspot... (Ensure Location & Wi-Fi are ON)",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = deck.textPrimary
                        )
                    }
                }
                is SoftApState.Failed -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                        border = BorderStroke(1.dp, deck.signalAlert),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "⚠️ HOTSPOT ERROR\n${(softApState as SoftApState.Failed).reason}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = deck.signalAlert,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    NxfrService.softApManager?.resetState()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = deck.signalBeam,
                                    contentColor = deck.rootBackground
                                ),
                                shape = RoundedCornerShape(2.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                            ) {
                                Text(
                                    text = "[TRY AGAIN]",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Client Join State Observation
            when (clientJoinState) {
                is ClientJoinState.Connecting -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = deck.signalBeam, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Connecting to network...",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = deck.textPrimary
                            )
                        }
                        TextButton(onClick = { NxfrService.softApManager?.leaveNetwork() }) {
                            Text("CANCEL", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = deck.signalAlert)
                        }
                    }
                }
                is ClientJoinState.Connected -> {
                    val connectedState = clientJoinState as ClientJoinState.Connected
                    LaunchedEffect(clientJoinState) {
                        onConnectToNode("${connectedState.hostIp}:17394")
                    }
                }
                is ClientJoinState.Failed -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                        border = BorderStroke(1.dp, deck.signalAlert),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "⚠️ FAILED TO JOIN: ${(clientJoinState as ClientJoinState.Failed).reason}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = deck.signalAlert
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = { NxfrService.softApManager?.resetState() }) {
                                Text("TRY AGAIN", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = deck.signalBeam)
                            }
                        }
                    }
                }
                else -> {}
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
