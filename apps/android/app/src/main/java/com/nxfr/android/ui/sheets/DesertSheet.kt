package com.nxfr.android.ui.sheets

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.nxfr.android.discovery.DesertState
import com.nxfr.android.service.NxfrService
import com.nxfr.android.transfer.NxfrQrTicketParser
import com.nxfr.android.transfer.QrScanResult
import com.nxfr.android.ui.theme.deckColors
import com.nxfr.android.ui.util.QrBitmapGenerator
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

    val orchestrator = NxfrService.desertOrchestrator
    val desertState by (orchestrator?.state ?: MutableStateFlow(DesertState.Idle)).collectAsState()

    var permissionGranted by remember { mutableStateOf(hasPermission(context)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }

    // QR Scanner launcher
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { content ->
            val parsed = NxfrQrTicketParser.parse(content)
            if (parsed is QrScanResult.DesertTicket) {
                orchestrator?.joinFromQr(parsed.ssid, parsed.pw.ifEmpty { null }, parsed.ip, parsed.port)
                NxfrService.setDesertSessionActive(true)
            }
        }
    }

    // Auto-connect when orchestrator reaches Connected state
    LaunchedEffect(desertState) {
        if (desertState is DesertState.Connected) {
            val connected = desertState as DesertState.Connected
            if (!connected.isGroupOwner) {
                delay(300) // Let network settle
                onConnectToNode("${connected.peerIp}:17394")
            } else {
                // GO: ensure listener is active
                if (!NxfrService.isListening.value) {
                    val intent = Intent(context, NxfrService::class.java)
                    context.startService(intent)
                    NxfrService.startListening(context)
                }
            }
        }
    }

    // Ensure listener is active when hosting in Tier 2
    LaunchedEffect(desertState) {
        if (desertState is DesertState.Tier2Hosting) {
            if (!NxfrService.isListening.value) {
                val intent = Intent(context, NxfrService::class.java)
                context.startService(intent)
                NxfrService.startListening(context)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            // Don't tear down if connected — just close the sheet
            if (desertState !is DesertState.Connected) {
                orchestrator?.reset()
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
                .padding(bottom = 16.dp)
        ) {
            // Header
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
                // Tier badge
                val tierLabel = when (desertState) {
                    is DesertState.Idle, is DesertState.LocationRequired -> "[OFF-GRID]"
                    is DesertState.Tier1Scanning, is DesertState.Tier1PeersFound, is DesertState.Tier1Connecting -> "[TIER 1 · P2P]"
                    is DesertState.Tier2Starting, is DesertState.Tier2Hosting, is DesertState.Tier2Joining -> "[TIER 2 · AP]"
                    is DesertState.Tier3QrFallback -> "[TIER 3 · QR]"
                    is DesertState.Connected -> "[LINKED]"
                    is DesertState.Failed -> "[ERROR]"
                }
                Box(
                    modifier = Modifier
                        .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = tierLabel,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = deck.signalBeam
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Direct peer-to-peer without internet. Both devices need Wi-Fi radio on.",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = deck.textSecondary
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Permission Gate
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
                            modifier = Modifier.fillMaxWidth().height(48.dp)
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
                return@Column
            }

            // Main state-driven UI
            when (val state = desertState) {

                // ── IDLE: Big enter button ──
                is DesertState.Idle -> {
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
                            orchestrator?.start(aid, NxfrService.deviceName.value)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = deck.signalBeam,
                            contentColor = deck.rootBackground
                        ),
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text(
                            text = "\uD83D\uDCE1 ENTER DESERT MODE",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Scan QR shortcut for when the other device is already hosting
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val opts = ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt("Scan the QR code from the host device")
                                setBeepEnabled(false)
                                setOrientationLocked(true)
                            }
                            scanLauncher.launch(opts)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = deck.textPrimary),
                        border = BorderStroke(1.dp, deck.gridLine),
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SCAN QR FROM OTHER DEVICE",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // ── LOCATION REQUIRED ──
                is DesertState.LocationRequired -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                        border = BorderStroke(1.dp, deck.signalWarning),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "\u26A0\uFE0F LOCATION SERVICES REQUIRED\nAndroid ${Build.VERSION.SDK_INT} requires Location ON for Wi-Fi scanning.",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = deck.signalWarning,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        })
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = deck.signalBeam,
                                        contentColor = deck.rootBackground
                                    ),
                                    shape = RoundedCornerShape(2.dp),
                                    modifier = Modifier.weight(1f).height(40.dp)
                                ) {
                                    Text("OPEN SETTINGS", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { orchestrator?.retryAfterLocation() },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = deck.signalBeam),
                                    border = BorderStroke(1.dp, deck.signalBeam),
                                    shape = RoundedCornerShape(2.dp),
                                    modifier = Modifier.weight(1f).height(40.dp)
                                ) {
                                    Text("RETRY", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // ── TIER 1: SCANNING ──
                is DesertState.Tier1Scanning -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                        border = BorderStroke(1.dp, deck.signalBeam),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            LinearProgressIndicator(
                                progress = { 1f - (state.secondsRemaining / 10f) },
                                color = deck.signalBeam,
                                trackColor = deck.surfaceContainer,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Scanning for nearby stations (Wi-Fi Direct)...\n${state.secondsRemaining}s remaining — auto-fallback to hotspot",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = deck.textPrimary,
                                lineHeight = 16.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { orchestrator?.reset(); NxfrService.setDesertSessionActive(false) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = deck.signalAlert),
                        border = BorderStroke(1.dp, deck.signalAlert),
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text("[CANCEL]", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // ── TIER 1: PEERS FOUND ──
                is DesertState.Tier1PeersFound -> {
                    Text(
                        text = "DISCOVERED STATIONS (${state.peers.size})",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.signalBeam
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                    ) {
                        items(state.peers) { peer ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                                border = BorderStroke(1.dp, deck.gridLine),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = peer.deviceName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = deck.textPrimary
                                        )
                                        Text(
                                            text = peer.deviceAddress,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = deck.textSecondary
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            orchestrator?.connectToPeer(peer)
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = deck.signalBeam,
                                            contentColor = deck.rootBackground
                                        ),
                                        shape = RoundedCornerShape(2.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Text("CONNECT", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            }
                        }
                    }
                }

                // ── TIER 1: CONNECTING / TIER 2: STARTING / JOINING ──
                is DesertState.Tier1Connecting, is DesertState.Tier2Starting, is DesertState.Tier2Joining -> {
                    val label = when (state) {
                        is DesertState.Tier1Connecting -> "Forming direct link..."
                        is DesertState.Tier2Starting -> "Starting local hotspot..."
                        is DesertState.Tier2Joining -> "Joining network..."
                        else -> "Connecting..."
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = deck.signalBeam, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = label,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = deck.textPrimary
                        )
                    }
                }

                // ── TIER 2: HOSTING (QR visible) ──
                is DesertState.Tier2Hosting -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                        border = BorderStroke(1.dp, deck.signalBeam),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "\u26A1 HOTSPOT ACTIVE \u2014 HOST MODE",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = deck.signalBeam
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "SSID: ${state.ssid}\nPASS: ${state.passphrase ?: "(none)"}\nHOST: ${state.hostIp}:17394",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = deck.textPrimary,
                                lineHeight = 15.sp
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // QR code for the other device to scan
                            val qrBitmap = remember(state.qrPayload) {
                                QrBitmapGenerator.generate(state.qrPayload, 400)
                            }
                            qrBitmap?.let { bmp ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.size(200.dp)
                                ) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Desert Mode QR",
                                        modifier = Modifier.fillMaxSize().padding(8.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Other device: scan this QR to connect",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = deck.textDim,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            orchestrator?.teardown()
                            NxfrService.setDesertSessionActive(false)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = deck.signalAlert),
                        border = BorderStroke(1.dp, deck.signalAlert),
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Text("[STOP HOSTING]", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }

                // ── TIER 3: QR FALLBACK ──
                is DesertState.Tier3QrFallback -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                        border = BorderStroke(1.dp, deck.signalWarning),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "\u26A0\uFE0F AUTO-DISCOVERY FAILED\n${state.reason}\n\nAsk the other device to show their QR code, then scan it.",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = deck.signalWarning,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val opts = ScanOptions().apply {
                                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        setPrompt("Scan the QR code from the host device")
                                        setBeepEnabled(false)
                                        setOrientationLocked(true)
                                    }
                                    scanLauncher.launch(opts)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = deck.signalBeam,
                                    contentColor = deck.rootBackground
                                ),
                                shape = RoundedCornerShape(2.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("[SCAN QR CODE]", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    orchestrator?.reset()
                                    NxfrService.setDesertSessionActive(false)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = deck.textSecondary),
                                border = BorderStroke(1.dp, deck.gridLine),
                                shape = RoundedCornerShape(2.dp),
                                modifier = Modifier.fillMaxWidth().height(40.dp)
                            ) {
                                Text("[RETRY FROM START]", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // ── CONNECTED ──
                is DesertState.Connected -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                        border = BorderStroke(1.dp, deck.signalBeam),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "\uD83D\uDCE1 DESERT LINK ACTIVE",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = deck.signalBeam
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "PEER  : ${state.peerIp}:17394\nROLE  : ${if (state.isGroupOwner) "HOST" else "CLIENT"}\nTIER  : ${state.tier} ${if (state.tier == 1) "(Wi-Fi Direct)" else "(Hotspot)"}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = deck.textPrimary,
                                lineHeight = 15.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            orchestrator?.teardown()
                            NxfrService.setDesertSessionActive(false)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = deck.signalAlert),
                        border = BorderStroke(1.dp, deck.signalAlert),
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("[END DESERT LINK]", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }

                // ── FAILED ──
                is DesertState.Failed -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = deck.surfaceVariant),
                        border = BorderStroke(1.dp, deck.signalAlert),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "\u26A0\uFE0F CONNECTION FAILED\n${state.reason}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = deck.signalAlert,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        orchestrator?.reset()
                                        val aid = NxfrService.deviceId.value.let { did ->
                                            if (did.isNotEmpty()) {
                                                try {
                                                    val dateStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                                                    NxfrService.NxfrBridge.nxfr_advertised_id(did, dateStr)
                                                } catch (_: Throwable) { did.take(8) }
                                            } else "unknown"
                                        }
                                        NxfrService.setDesertSessionActive(true)
                                        orchestrator?.start(aid, NxfrService.deviceName.value)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = deck.signalBeam,
                                        contentColor = deck.rootBackground
                                    ),
                                    shape = RoundedCornerShape(2.dp),
                                    modifier = Modifier.weight(1f).height(40.dp)
                                ) {
                                    Text("[RETRY]", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = {
                                        orchestrator?.reset()
                                        NxfrService.setDesertSessionActive(false)
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = deck.textSecondary),
                                    border = BorderStroke(1.dp, deck.gridLine),
                                    shape = RoundedCornerShape(2.dp),
                                    modifier = Modifier.weight(1f).height(40.dp)
                                ) {
                                    Text("[DISMISS]", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
