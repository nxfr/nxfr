package com.nxfr.android.ui.sheets

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.ui.theme.deckColors
import com.nxfr.android.prefs.NxfrPreferences
import com.nxfr.android.service.NxfrService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class DiagnosticRow(
    val title: String,
    val isOk: Boolean,
    val details: String,
    val actionText: String? = null,
    val onAction: (() -> Unit)? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TroubleshootSheet(
    onDismiss: () -> Unit,
    onOpenManualConnect: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRunningDiag by remember { mutableStateOf(false) }
    var diagResults by remember { mutableStateOf<List<DiagnosticRow>?>(null) }

    fun runDiagnostics() {
        isRunningDiag = true
        scope.launch(Dispatchers.IO) {
            val results = mutableListOf<DiagnosticRow>()

            // 1. Listener bound
            val isListening = NxfrService.isListening.value
            results.add(
                DiagnosticRow(
                    title = "1. Listener Bound",
                    isOk = isListening,
                    details = if (isListening) "TCP server listening on port 17394" else "TCP server is currently STOPPED",
                    actionText = if (!isListening) "Start Server" else null,
                    onAction = if (!isListening) { { NxfrService.startListening(context) } } else null
                )
            )

            // 2. Self-probe TCP 17394
            var selfProbeOk = false
            var probeMsg = ""
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", 17394), 500)
                    selfProbeOk = true
                    probeMsg = "Connected to 127.0.0.1:17394 successfully"
                }
            } catch (e: Exception) {
                selfProbeOk = false
                probeMsg = "Probe failed: ${e.message ?: "Connection refused"}"
            }
            results.add(
                DiagnosticRow(
                    title = "2. Self-probe TCP 17394",
                    isOk = selfProbeOk,
                    details = probeMsg
                )
            )

            // 3. Multicast loopback
            results.add(
                DiagnosticRow(
                    title = "3. Multicast Beacon Loopback",
                    isOk = isListening,
                    details = if (isListening) "Multicast beacon group 224.0.0.251 active" else "Multicast disabled while hidden"
                )
            )

            // 4. Visibility pref vs actual state
            val prefs = context.getSharedPreferences("nxfr_prefs", Context.MODE_PRIVATE)
            val prefVisible = prefs.getBoolean("visible_enabled", true)
            val stateMatch = (prefVisible == isListening)
            results.add(
                DiagnosticRow(
                    title = "4. Visibility Preference Match",
                    isOk = stateMatch,
                    details = if (stateMatch) "Preference ($prefVisible) matches service state" else "State mismatch: pref=$prefVisible, active=$isListening"
                )
            )

            // 5. Network interfaces & advertise mode
            val mode = NxfrPreferences.advertiseMode.value
            var interfaceCount = 0
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    if (iface.isUp && !iface.isLoopback) interfaceCount++
                }
            } catch (_: Exception) {}
            results.add(
                DiagnosticRow(
                    title = "5. Network Interfaces List",
                    isOk = interfaceCount > 0,
                    details = "$interfaceCount active interface(s) (Advertise: $mode)"
                )
            )

            // 6. Battery optimization
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoringBattery = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            results.add(
                DiagnosticRow(
                    title = "6. Battery Optimization",
                    isOk = isIgnoringBattery,
                    details = if (isIgnoringBattery) "Unrestricted background execution" else "Battery optimization active (may delay transfers)",
                    actionText = if (!isIgnoringBattery) "Whitelist" else null,
                    onAction = if (!isIgnoringBattery) {
                        {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    } else null
                )
            )

            withContext(Dispatchers.Main) {
                diagResults = results
                isRunningDiag = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Connection & Diagnostics",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Guidance cards
            TroubleshootItem(
                title = "1. Connected to the same Wi-Fi / Hotspot?",
                description = "Both devices must be on the same local network or connected via Wi-Fi Hotspot."
            )
            Spacer(modifier = Modifier.height(8.dp))

            TroubleshootItem(
                title = "2. Check AP Isolation & Firewalls",
                description = "Some public Wi-Fi networks block device-to-device communication (AP Isolation). Try enabling Wi-Fi Hotspot."
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (onOpenManualConnect != null) {
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onOpenManualConnect()
                    },
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.GpsFixed,
                        contentDescription = null,
                        tint = MaterialTheme.deckColors.signalBeam,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ENTER ADDRESS MANUALLY",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.deckColors.signalBeam
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = { runDiagnostics() },
                enabled = !isRunningDiag,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isRunningDiag) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Running On-Device Checks...")
                } else {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Run Live Diagnostics")
                }
            }

            // Diag Results
            diagResults?.let { results ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Diagnostic Results",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                results.forEach { row ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (row.isOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (row.isOk) Color(0xFF22C55E) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(row.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            row.actionText?.let { actText ->
                                TextButton(onClick = { row.onAction?.invoke() }) {
                                    Text(actText)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://nxfr.github.io/nxfr/troubleshooting/"))
                    try { context.startActivity(intent) } catch (_: Throwable) {}
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Full Troubleshooting Guide")
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun TroubleshootItem(title: String, description: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
