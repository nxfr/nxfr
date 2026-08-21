package com.nxfr.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.R
import com.nxfr.android.prefs.NxfrPreferences
import com.nxfr.android.service.NxfrService
import com.nxfr.android.ui.dialogs.ColorPickerDialog
import com.nxfr.android.ui.icons.NxfrIcons
import com.nxfr.android.ui.sheets.TroubleshootSheet
import com.nxfr.android.ui.theme.ThemePreference
import com.nxfr.android.ui.theme.deckColors
import com.nxfr.android.utils.DebugBundleExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    deviceName: String,
    deviceId: String,
    onDeviceNameChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val deck = MaterialTheme.deckColors
    val storeDir = NxfrService.getIdentityDir(context)
    val coroutineScope = rememberCoroutineScope()
    val sharedPrefs = remember { context.getSharedPreferences("nxfr_prefs", Context.MODE_PRIVATE) }
    val scrollState = rememberScrollState()

    var isEditingName by remember { mutableStateOf(false) }
    var editNameValue by remember { mutableStateOf(deviceName) }

    // Auto accept & folder picker states
    var autoAcceptState by remember { mutableIntStateOf(sharedPrefs.getInt("auto_accept_global", 0)) }
    var showAutoAcceptWarning by remember { mutableStateOf(false) }
    var saveFolderPath by remember { mutableStateOf(sharedPrefs.getString("save_folder_uri", null)) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            saveFolderPath = uri.toString()
            sharedPrefs.edit().putString("save_folder_uri", uri.toString()).apply()
        }
    }

    // Dialog state flags
    var showColorPicker by remember { mutableStateOf(false) }
    var showEncryptionWhy by remember { mutableStateOf(false) }
    var showRevokeAllConfirm by remember { mutableStateOf(false) }
    var showTroubleshootSheet by remember { mutableStateOf(false) }
    var deviceToUnpair by remember { mutableStateOf<PairedDevice?>(null) }

    var pairedDevices by remember { mutableStateOf<List<PairedDevice>>(emptyList()) }

    fun refreshPairedDevices() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = NxfrService.NxfrBridge.nxfr_paired_list(storeDir)
                val json = JSONObject(jsonStr)
                if (json.has("devices")) {
                    val devicesArray = json.getJSONArray("devices")
                    val list = mutableListOf<PairedDevice>()
                    for (i in 0 until devicesArray.length()) {
                        val devObj = devicesArray.getJSONObject(i)
                        list.add(
                            PairedDevice(
                                deviceId = devObj.getString("device_id"),
                                name = devObj.getString("name"),
                                autoAccept = devObj.optString("auto_accept") == "always"
                            )
                        )
                    }
                    withContext(Dispatchers.Main) { pairedDevices = list }
                }
            } catch (e: Throwable) {
                Log.e("SettingsScreen", "Failed to load paired devices: ${e.message}", e)
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshPairedDevices()
    }

    // ── Dialogs ──
    if (showColorPicker) {
        val currentSeed = ColorPreferenceHelper.getCustomSeedColor(context)
        ColorPickerDialog(
            initialColor = androidx.compose.ui.graphics.Color(currentSeed),
            onDismiss = { showColorPicker = false },
            onColorSelected = { selectedColor ->
                ThemePreference.setCustomSeedColor(context, selectedColor.toArgb())
                ThemePreference.setColorMode(context, ThemePreference.COLOR_MODE_CUSTOM)
            }
        )
    }

    if (showEncryptionWhy) {
        AlertDialog(
            onDismissRequest = { showEncryptionWhy = false },
            title = { Text("Encryption Invariant [SEALED]", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Why can't I disable encryption?\n\nNXFR requires end-to-end security for all data transfers. All pipes are mTLS 1.3 authenticated with ephemeral curve25519 session keys.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showEncryptionWhy = false }) {
                    Text("ACKNOWLEDGED", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showRevokeAllConfirm) {
        AlertDialog(
            onDismissRequest = { showRevokeAllConfirm = false },
            title = { Text("Revoke All Paired Keys?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = { Text("All pinned node certificates will be purged from the trust store. Future transmissions will require explicit TOFU authorization.", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            pairedDevices.forEach { dev ->
                                try { NxfrService.NxfrBridge.nxfr_unpair(storeDir, dev.deviceId) } catch (_: Throwable) {}
                            }
                            withContext(Dispatchers.Main) {
                                pairedDevices = emptyList()
                                showRevokeAllConfirm = false
                                Toast.makeText(context, "Trust store purged", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = deck.signalAlert)
                ) {
                    Text("REVOKE ALL", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRevokeAllConfirm = false }) {
                    Text("CANCEL", fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    deviceToUnpair?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToUnpair = null },
            title = { Text("Revoke Node ${device.name}?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = { Text("Pinned certificate did:nxfr:${device.deviceId.take(8)} will be removed from trust store.", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            try { NxfrService.NxfrBridge.nxfr_unpair(storeDir, device.deviceId) } catch (_: Throwable) {}
                            withContext(Dispatchers.Main) {
                                deviceToUnpair = null
                                refreshPairedDevices()
                                Toast.makeText(context, "Unpaired ${device.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = deck.signalAlert)
                ) {
                    Text("REVOKE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { deviceToUnpair = null }) {
                    Text("CANCEL", fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(deck.rootBackground)
            .verticalScroll(scrollState)
    ) {
        // ── 1. IDENTITY LEDGER BLOCK ─────────────────────────────────────────
        LedgerSectionHeader(title = "STATION IDENTITY & KEYS")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .background(deck.surface, RoundedCornerShape(4.dp))
                .border(1.dp, deck.gridLine, RoundedCornerShape(4.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isEditingName) {
                    OutlinedTextField(
                        value = editNameValue,
                        onValueChange = { editNameValue = it },
                        label = { Text("Device Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { isEditingName = false }) { Text("CANCEL") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (editNameValue.isNotBlank()) {
                                    onDeviceNameChanged(editNameValue)
                                }
                                isEditingName = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = deck.signalBeam, contentColor = deck.rootBackground)
                        ) { Text("SAVE", fontWeight = FontWeight.Bold) }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = deviceName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = deck.textPrimary
                            )
                            Text(
                                text = "did:nxfr:${deviceId.take(16)}…",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = deck.textSecondary
                            )
                        }
                        IconButton(onClick = {
                            editNameValue = deviceName
                            isEditingName = true
                        }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit call-sign", tint = deck.signalBeam)
                        }
                    }
                }
            }
        }

        // ── 2. GENERAL SECTION ───────────────────────────────────────────────
        LedgerSectionHeader(title = "CONSOLE & PRESENTATION")

        LedgerRow(title = "Interface Theme", subtitle = "Dark or light theme") {
            val themeMode by ThemePreference.themeMode.collectAsState()
            val themeOptions = listOf("Auto", "Dark", "Light")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.width(200.dp)) {
                themeOptions.forEachIndexed { index, label ->
                    val optMode = when (index) {
                        1 -> ThemePreference.DARK
                        2 -> ThemePreference.LIGHT
                        else -> ThemePreference.SYSTEM
                    }
                    SegmentedButton(
                        selected = themeMode == optMode,
                        onClick = { ThemePreference.setTheme(context, optMode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = themeOptions.size)
                    ) {
                        Text(label, fontSize = 11.sp)
                    }
                }
            }
        }

        LedgerRow(title = "Color Mode", subtitle = "Brand cyan, pure OLED black, or custom seed") {
            val colorMode by ThemePreference.colorMode.collectAsState()
            val colorOptions = listOf("Brand", "OLED", "Custom")
            val colorIndex = when (colorMode) {
                ThemePreference.COLOR_MODE_OLED -> 1
                ThemePreference.COLOR_MODE_CUSTOM -> 2
                else -> 0
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.width(220.dp)) {
                colorOptions.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = colorIndex == index,
                        onClick = {
                            val selectedMode = when (index) {
                                1 -> {
                                    ThemePreference.setTheme(context, ThemePreference.DARK)
                                    ThemePreference.COLOR_MODE_OLED
                                }
                                2 -> {
                                    showColorPicker = true
                                    ThemePreference.COLOR_MODE_CUSTOM
                                }
                                else -> ThemePreference.COLOR_MODE_BRAND
                            }
                            ThemePreference.setColorMode(context, selectedMode)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = colorOptions.size)
                    ) {
                        Text(label, fontSize = 11.sp)
                    }
                }
            }
        }

        LedgerRow(title = "Interface Animations", subtitle = "Respects system ANIMATOR_DURATION_SCALE") {
            val appAnimationsEnabled by com.nxfr.android.ui.theme.AnimationPreference.animationsEnabled.collectAsState()
            Switch(
                checked = appAnimationsEnabled,
                onCheckedChange = { com.nxfr.android.ui.theme.AnimationPreference.setAnimationsEnabled(context, it) }
            )
        }

        // ── 3. RECEIVE SECTION ───────────────────────────────────────────────
        LedgerSectionHeader(title = "RECEIVE INVARIANTS")

        LedgerRow(title = "Save Media to Gallery", subtitle = "Index images/videos into Android media store") {
            val saveToGallery by NxfrPreferences.saveToGallery.collectAsState()
            Switch(
                checked = saveToGallery,
                onCheckedChange = { NxfrPreferences.setSaveToGallery(context, it) }
            )
        }

        LedgerRow(title = "Local Session History", subtitle = "Records never leave this device") {
            val saveToHistory by NxfrPreferences.saveToHistory.collectAsState()
            Switch(
                checked = saveToHistory,
                onCheckedChange = { NxfrPreferences.setSaveToHistory(context, it) }
            )
        }

        LedgerRow(title = "Auto-Finish Completed Sheet", subtitle = "Dismisses sheet 1.5s after 100% check") {
            val autoFinish by NxfrPreferences.autoFinish.collectAsState()
            Switch(
                checked = autoFinish,
                onCheckedChange = { NxfrPreferences.setAutoFinish(context, it) }
            )
        }

        LedgerRow(title = "Collision Rename", subtitle = "Duplicate files saved as name (1).ext") {
            val collisionRename by NxfrPreferences.collisionRename.collectAsState()
            Switch(
                checked = collisionRename,
                onCheckedChange = { NxfrPreferences.setCollisionRename(context, it) }
            )
        }

        LedgerRow(
            title = "Destination Folder",
            subtitle = saveFolderPath ?: "Downloads/NXFR (Default)",
            onClick = { folderPicker.launch(null) }
        ) {
            Icon(NxfrIcons.Folder, contentDescription = "Pick folder", tint = deck.signalBeam)
        }

        // ── 4. SEND SECTION ──────────────────────────────────────────────────
        LedgerSectionHeader(title = "SEND INVARIANTS")

        LedgerRow(title = "Default Send Mode", subtitle = "Single recipient clears selection on complete") {
            val defaultSendMode by NxfrPreferences.defaultSendMode.collectAsState()
            val modes = listOf("Single", "Multiple")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.width(180.dp)) {
                modes.forEachIndexed { index, label ->
                    val modeKey = if (index == 0) "single" else "multiple"
                    SegmentedButton(
                        selected = defaultSendMode == modeKey,
                        onClick = { NxfrPreferences.setDefaultSendMode(context, modeKey) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                    ) {
                        Text(label, fontSize = 11.sp)
                    }
                }
            }
        }

        LedgerRow(title = "SHA-256 Integrity Verification", subtitle = "Compute and display full payload checksum") {
            val showChecksum by NxfrPreferences.showChecksum.collectAsState()
            Switch(
                checked = showChecksum,
                onCheckedChange = { NxfrPreferences.setShowChecksum(context, it) }
            )
        }

        // ── 5. NETWORK SECTION ───────────────────────────────────────────────
        LedgerSectionHeader(title = "SOCKET & BEACON PARAMETERS")

        var portInput by remember { mutableStateOf(NxfrPreferences.port.value.toString()) }
        var timeoutInput by remember { mutableStateOf(NxfrPreferences.discoveryTimeoutMs.value.toString()) }
        var multicastInput by remember { mutableStateOf(NxfrPreferences.multicastAddress.value) }

        LedgerRow(title = "TCP Protocol Port", subtitle = "Default 17394 (Range: 1024–65535)") {
            OutlinedTextField(
                value = portInput,
                onValueChange = { newVal ->
                    portInput = newVal
                    val parsed = newVal.toIntOrNull()
                    if (parsed != null && parsed in 1024..65535) {
                        NxfrPreferences.setPort(context, parsed)
                        NxfrService.updateActivePortAndRebind(context, parsed)
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.width(110.dp)
            )
        }

        LedgerRow(title = "Beacon Discovery Timeout", subtitle = "Wait time for responses (500–15000 ms)") {
            OutlinedTextField(
                value = timeoutInput,
                onValueChange = { newVal ->
                    timeoutInput = newVal
                    val parsed = newVal.toIntOrNull()
                    if (parsed != null && parsed in 500..15000) {
                        NxfrPreferences.setDiscoveryTimeoutMs(context, parsed.toLong())
                        NxfrService.discoveryTimeoutMs = parsed
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.width(110.dp)
            )
        }

        LedgerRow(title = "Multicast Group Address", subtitle = "Default 224.0.0.251") {
            OutlinedTextField(
                value = multicastInput,
                onValueChange = { newVal ->
                    multicastInput = newVal
                    if (newVal.startsWith("224.") || newVal.startsWith("239.")) {
                        NxfrPreferences.setMulticastAddress(context, newVal)
                        NxfrService.updateMulticastAddressAndRebind(context, newVal)
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.width(140.dp)
            )
        }

        LedgerRow(
            title = "Always-On TLS 1.3 Pipeline",
            subtitle = "Encryption invariant is hard-sealed by design",
            onClick = { showEncryptionWhy = true }
        ) {
            SecuritySeal(text = "SEALED: TLS 1.3", color = deck.signalSuccess)
        }

        // ── 6. SECURITY SECTION ──────────────────────────────────────────────
        LedgerSectionHeader(title = "CRYPTOGRAPHIC TRUST STORE")

        LedgerRow(title = "Auto-Accept Policy", subtitle = "Off, Paired nodes, or Everyone") {
            val autoOptions = listOf("Off", "Paired", "All")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.width(180.dp)) {
                autoOptions.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = autoAcceptState == index,
                        onClick = {
                            if (index == 2 && autoAcceptState != 2) {
                                showAutoAcceptWarning = true
                            } else {
                                autoAcceptState = index
                                sharedPrefs.edit().putInt("auto_accept_global", index).apply()
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = autoOptions.size)
                    ) {
                        Text(label, fontSize = 11.sp)
                    }
                }
            }
        }

        if (showAutoAcceptWarning) {
            AlertDialog(
                onDismissRequest = { showAutoAcceptWarning = false },
                title = { Text("Auto-Accept Warning", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                text = { Text("All paired devices will be able to transmit files without explicit prompt confirmation.", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                confirmButton = {
                    Button(onClick = {
                        autoAcceptState = 2
                        sharedPrefs.edit().putInt("auto_accept_global", 2).apply()
                        showAutoAcceptWarning = false
                    }, colors = ButtonDefaults.buttonColors(containerColor = deck.signalAlert)) {
                        Text("CONFIRM", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showAutoAcceptWarning = false }) {
                        Text("CANCEL", fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        LedgerRow(
            title = "Pinned Peer Certificates",
            subtitle = if (pairedDevices.isEmpty()) "No trusted node certificates" else "${pairedDevices.size} certificates active in TOFU store",
            onClick = { if (pairedDevices.isNotEmpty()) showRevokeAllConfirm = true }
        ) {
            if (pairedDevices.isNotEmpty()) {
                SecuritySeal(text = "TRUSTED: ${pairedDevices.size}", color = deck.signalBeam)
            } else {
                SecuritySeal(text = "EMPTY STORE", color = deck.textDim)
            }
        }

        if (pairedDevices.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                pairedDevices.forEach { dev ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(dev.name, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = deck.textPrimary)
                            Text("did:nxfr:${dev.deviceId.take(12)}…", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = deck.textDim)
                        }
                        IconButton(onClick = { deviceToUnpair = dev }) {
                            Icon(Icons.Default.Delete, contentDescription = "Unpair", tint = deck.signalAlert)
                        }
                    }
                }
            }
        }

        // ── 7. DIAGNOSTICS & TELEMETRY ───────────────────────────────────────
        LedgerSectionHeader(title = "SYSTEM DIAGNOSTICS & AUDIT")

        LedgerRow(
            title = "Live Diagnostic Console",
            subtitle = "Inspect socket bindings, discovery beacons, and power policies",
            onClick = { showTroubleshootSheet = true }
        ) {
            Icon(NxfrIcons.Diagnostics, contentDescription = "Diagnostics", tint = deck.signalBeam)
        }

        LedgerRow(
            title = "Export Diagnostic Bundle",
            subtitle = "Redacted system log and socket report",
            onClick = {
                DebugBundleExporter.exportDebugBundle(context)
            }
        ) {
            Icon(NxfrIcons.Receive, contentDescription = "Export bundle", tint = deck.signalBeam)
        }

        // ── 8. ABOUT & PROTOCOL ──────────────────────────────────────────────
        LedgerSectionHeader(title = "PROTOCOL SPECIFICATION")

        LedgerRow(
            title = "NXFR Protocol Engine",
            subtitle = "v${com.nxfr.android.BuildConfig.VERSION_NAME} [BUILD ${com.nxfr.android.BuildConfig.VERSION_CODE}] · Rust TLS 1.3 Core"
        ) {
            SecuritySeal(text = "VERIFIED", color = deck.signalSuccess)
        }

        LedgerRow(
            title = "GitHub Repository",
            subtitle = "github.com/nxfr/nxfr",
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nxfr/nxfr")))
                } catch (_: Exception) {}
            }
        ) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "GitHub", tint = deck.textSecondary)
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showTroubleshootSheet) {
        TroubleshootSheet(onDismiss = { showTroubleshootSheet = false })
    }
}

@Composable
private fun LedgerSectionHeader(title: String) {
    val deck = MaterialTheme.deckColors
    Text(
        text = title,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        color = deck.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp)
    )
}

@Composable
private fun LedgerRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    val deck = MaterialTheme.deckColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .border(width = 0.5.dp, color = deck.gridLine)
            .background(deck.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = deck.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = deck.textSecondary
            )
        }

        trailing()
    }
}

@Composable
private fun SecuritySeal(text: String, color: androidx.compose.ui.graphics.Color) {
    val deck = MaterialTheme.deckColors
    Box(
        modifier = Modifier
            .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
            .border(0.5.dp, color, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "[$text]",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

data class PairedDevice(
    val deviceId: String,
    val name: String,
    val autoAccept: Boolean
)

object ColorPreferenceHelper {
    fun getCustomSeedColor(context: Context): Int {
        val prefs = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("custom_seed_color", 0xFF00E5FF.toInt())
    }
}
