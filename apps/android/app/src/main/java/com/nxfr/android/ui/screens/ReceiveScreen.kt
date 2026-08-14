package com.nxfr.android.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.discovery.NetworkInterfaceHelper
import com.nxfr.android.prefs.NxfrPreferences
import com.nxfr.android.service.NxfrService
import com.nxfr.android.ui.components.*
import com.nxfr.android.ui.sheets.DesertSheet
import com.nxfr.android.ui.sheets.HistorySheet
import com.nxfr.android.ui.sheets.TroubleshootSheet
import com.nxfr.android.ui.theme.deckColors
import java.net.Inet4Address
import java.net.NetworkInterface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    deviceName: String = "My Device",
    deviceId: String = "",
    onDeviceNameChanged: (String) -> Unit = {},
    onReceiveViaLink: () -> Unit = {},
    onScanQr: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val deck = MaterialTheme.deckColors

    val isListening by NxfrService.isListening.collectAsState()

    var showInfoSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showTroubleshootSheet by remember { mutableStateOf(false) }
    var showDesertSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(deck.rootBackground)
            .verticalScroll(scrollState)
    ) {
        // 1. Top Telemetry Ribbon
        TelemetryRibbon(isListening = isListening)

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Identity Deck Bar
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            IdentityDeckBar(
                deviceName = deviceName,
                deviceId = deviceId,
                onDeviceNameChanged = onDeviceNameChanged,
                onShowInfo = { showInfoSheet = true }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3. The Core Motif: The BEAM Visualizer
        BeamVisualizer(isPowered = isListening)

        // 4. Physical Visibility Breaker
        BreakerSwitch(
            isEngaged = isListening,
            onToggle = { enabled ->
                val prefs = context.getSharedPreferences("nxfr_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("visible_enabled", enabled).apply()
                if (enabled) {
                    val intent = Intent(context, NxfrService::class.java)
                    context.startService(intent)
                    NxfrService.startListening(context)
                } else {
                    NxfrService.stopListening(context)
                }
            }
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 5. Action Rail
        ActionRail(
            isPowered = isListening,
            onReceiveViaLink = onReceiveViaLink,
            onScanQr = onScanQr,
            onOpenDiagnostics = { showTroubleshootSheet = true },
            onOpenDesert = { showDesertSheet = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 6. Recent Sessions Feed
        RecentSessionsCard(
            onOpenHistory = { showHistorySheet = true }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    // ── Modals & Sheets ───────────────────────────────────────────────
    if (showInfoSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showInfoSheet = false },
            sheetState = sheetState,
            containerColor = deck.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "STATION TELEMETRY",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = deck.signalBeam
                )

                Text(
                    text = "Device Call-Sign: $deviceName",
                    style = MaterialTheme.typography.bodyLarge,
                    color = deck.textPrimary
                )

                Text(
                    text = "Device ID: $deviceId",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = deck.textSecondary
                )

                HorizontalDivider(color = deck.gridLine)

                Text(
                    text = "NETWORK INTERFACES",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = deck.textSecondary
                )

                val ips = getDeviceIps(context)
                if (ips.isEmpty()) {
                    Text("No active interfaces", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = deck.textDim)
                } else {
                    ips.forEach { ip ->
                        Text(ip, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = deck.textPrimary)
                    }
                }

                HorizontalDivider(color = deck.gridLine)

                Text(
                    text = "SOCKET BINDINGS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = deck.textSecondary
                )
                Text("TCP 17394 — Direct mTLS 1.3 Transmission", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = deck.textPrimary)
                Text("TCP 17396 — Ephemeral Web-Upload Gateway", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = deck.textPrimary)
                Text("UDP 224.0.0.251:5353 — Hotspot Multicast Beacon", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = deck.textPrimary)

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showHistorySheet) {
        HistorySheet(onDismiss = { showHistorySheet = false })
    }

    if (showTroubleshootSheet) {
        TroubleshootSheet(onDismiss = { showTroubleshootSheet = false })
    }

    if (showDesertSheet) {
        DesertSheet(onDismiss = { showDesertSheet = false })
    }
}

private fun getDeviceIps(context: Context): List<String> {
    return NetworkInterfaceHelper.getOrderedLocalIps(context).map { "${it.second} (${it.first})" }
}
