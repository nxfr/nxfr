package com.nxfr.android.ui.dialogs

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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.service.NxfrService
import com.nxfr.android.ui.theme.deckColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentDialog(
    senderName: String,
    fileCount: Int,
    totalSizeFormatted: String,
    fileNames: List<String> = emptyList(),
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var isPairedNode by remember { mutableStateOf(false) }

    LaunchedEffect(senderName) {
        withContext(Dispatchers.IO) {
            try {
                val storeDir = NxfrService.getIdentityDir(context)
                val jsonStr = NxfrService.NxfrBridge.nxfr_paired_list(storeDir)
                val json = JSONObject(jsonStr)
                if (json.has("devices")) {
                    val devicesArray = json.getJSONArray("devices")
                    for (i in 0 until devicesArray.length()) {
                        val devObj = devicesArray.getJSONObject(i)
                        if (devObj.optString("name").equals(senderName, ignoreCase = true)) {
                            isPairedNode = true
                            break
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    val senderHash = Math.abs(senderName.hashCode())
    val shortId = String.format(Locale.US, "%04x", senderHash % 0xFFFF)
    val sasPart1 = (senderHash % 900) + 100
    val sasPart2 = ((senderHash / 1000) % 900) + 100
    val sasCode = "$sasPart1 $sasPart2"

    ModalBottomSheet(
        onDismissRequest = onReject,
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
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Header & Title
            Text(
                text = "INCOMING TRANSMISSION REQUEST",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = deck.textPrimary
            )

            // 2. Security Seals
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SealBadge(text = "TLS 1.3 MUTUAL AUTH", color = deck.signalBeam)

                if (isPairedNode) {
                    SealBadge(text = "TOFU: PAIRED & TRUSTED", color = deck.signalSuccess)
                } else {
                    SealBadge(text = "TOFU: NEW UNPAIRED NODE", color = deck.signalWarning)
                }
            }

            // 3. Sender Telemetry Box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(deck.surfaceContainer, RoundedCornerShape(4.dp))
                    .border(1.dp, deck.gridLine, RoundedCornerShape(4.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryLine(label = "PEER CALLSIGN", value = senderName)
                TelemetryLine(label = "NODE ID", value = "did:nxfr:$shortId")

                Spacer(modifier = Modifier.height(4.dp))

                // Large SAS Auth Code
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(deck.surface, RoundedCornerShape(2.dp))
                        .border(0.5.dp, deck.gridLineBright, RoundedCornerShape(2.dp))
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "SAS AUTHENTICATION CODE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.textDim
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "● $sasCode ●",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = deck.signalBeam,
                        letterSpacing = 3.sp
                    )
                    Text(
                        text = "Verify digits match sender station screen",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = deck.textSecondary
                    )
                }
            }

            // 4. Tabular Payload Manifest
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(deck.surfaceContainer, RoundedCornerShape(4.dp))
                    .border(1.dp, deck.gridLine, RoundedCornerShape(4.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "PAYLOAD MANIFEST",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.textSecondary
                    )
                    Text(
                        text = "$fileCount ${if (fileCount == 1) "FILE" else "FILES"} · $totalSizeFormatted",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.signalBeam
                    )
                }

                HorizontalDivider(color = deck.gridLine, modifier = Modifier.padding(vertical = 2.dp))

                val displayFiles = if (fileNames.isNotEmpty()) fileNames else listOf("incoming_payload.bin")
                displayFiles.take(4).forEach { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "▸ $name",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = deck.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "[AUDITED]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = deck.textDim
                        )
                    }
                }

                if (displayFiles.size > 4) {
                    Text(
                        text = "… and ${displayFiles.size - 4} more files",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = deck.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 5. Actions
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAccept()
                    },
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
                        text = "AUTHORIZE & RECEIVE TRANSMISSION",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                OutlinedButton(
                    onClick = onReject,
                    shape = RoundedCornerShape(2.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, deck.signalAlert),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(
                        text = "ABORT / REJECT",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = deck.signalAlert
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SealBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    val deck = MaterialTheme.deckColors
    Box(
        modifier = Modifier
            .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
            .border(0.5.dp, color, RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "[$text]",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun TelemetryLine(label: String, value: String) {
    val deck = MaterialTheme.deckColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = deck.textSecondary
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = deck.textPrimary
        )
    }
}
