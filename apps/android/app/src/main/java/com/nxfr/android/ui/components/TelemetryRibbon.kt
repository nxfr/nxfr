package com.nxfr.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.service.NxfrService
import com.nxfr.android.ui.theme.deckColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun TelemetryRibbon(
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors
    val context = LocalContext.current
    var pairedCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val storeDir = NxfrService.getIdentityDir(context)
                val jsonStr = NxfrService.NxfrBridge.nxfr_paired_list(storeDir)
                val json = JSONObject(jsonStr)
                if (json.has("devices")) {
                    pairedCount = json.getJSONArray("devices").length()
                }
            } catch (_: Throwable) {}
        }
    }

    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(deck.surfaceContainer)
            .border(width = 0.5.dp, color = deck.gridLine)
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Cipher Status
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(if (isListening) deck.signalBeam else deck.signalStandby, RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "TLS 1.3 ENCRYPTED",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isListening) deck.signalBeam else deck.textDim
            )
        }

        Text(text = "│", color = deck.gridLineBright, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

        // Socket Status
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isListening) "TCP 17394 [LISTEN]" else "TCP 17394 [STANDBY]",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (isListening) deck.textPrimary else deck.textDim
            )
        }

        Text(text = "│", color = deck.gridLineBright, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

        // Trust Store
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "TOFU: $pairedCount PAIRED",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (pairedCount > 0) deck.signalSuccess else deck.textSecondary
            )
        }
    }
}
