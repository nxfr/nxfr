package com.nxfr.android.battery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.ui.theme.deckColors

/**
 * Battery optimization checks and OEM exemption intents.
 */
object BatteryOptimizationHelper {

    private const val PREFS_NAME = "nxfr_prefs"
    private const val KEY_ONBOARDING_SHOWN = "battery_onboarding_shown"
    private const val KEY_BANNER_DISMISSED = "battery_banner_dismissed"

    /** Check if the app is whitelisted from battery optimization. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    /** Launch the system dialog to request battery optimization exemption. */
    fun requestBatteryExemption(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Some OEMs strip this intent. Fall back to app-specific battery settings.
            openBatterySettings(context)
        }
    }

    /** Open the app's battery settings page directly. */
    fun openBatterySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Last resort: open general battery settings.
            try {
                context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {}
        }
    }

    /** Whether the first-launch onboarding has been shown. */
    fun hasShownOnboarding(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_SHOWN, false)
    }

    /** Mark the onboarding as shown so it doesn't appear again. */
    fun markOnboardingShown(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDING_SHOWN, true).apply()
    }

    /** Whether the user explicitly dismissed the persistent banner. */
    fun isBannerDismissed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BANNER_DISMISSED, false)
    }

    /** Dismiss the persistent banner (user chose to ignore). */
    fun dismissBanner(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BANNER_DISMISSED, true).apply()
    }

    /** Reset the banner so it shows again (e.g. if user re-enables optimization). */
    fun resetBannerDismissal(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BANNER_DISMISSED, false).apply()
    }

    /** Detect OEM and return manufacturer-specific instructions. */
    fun getOemGuidance(): OemGuidance? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
                OemGuidance(
                    brand = "Xiaomi/Redmi",
                    instructions = "Security → Battery → App battery saver → NXFR → No restrictions",
                    deepIntentAction = "miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY"
                )
            manufacturer.contains("samsung") ->
                OemGuidance(
                    brand = "Samsung",
                    instructions = "Settings → Battery → Background usage limits → Never sleeping apps → Add NXFR",
                    deepIntentAction = null
                )
            manufacturer.contains("oppo") || manufacturer.contains("realme") ->
                OemGuidance(
                    brand = "OPPO/Realme",
                    instructions = "Settings → Battery → More battery settings → Optimize battery use → NXFR → Don't optimize",
                    deepIntentAction = null
                )
            manufacturer.contains("vivo") ->
                OemGuidance(
                    brand = "Vivo",
                    instructions = "Settings → Battery → Background power consumption → NXFR → Allow",
                    deepIntentAction = null
                )
            manufacturer.contains("oneplus") ->
                OemGuidance(
                    brand = "OnePlus",
                    instructions = "Settings → Battery → Battery optimization → NXFR → Don't optimize",
                    deepIntentAction = null
                )
            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                OemGuidance(
                    brand = "Huawei/Honor",
                    instructions = "Settings → Battery → App launch → NXFR → Manage manually → Enable all toggles",
                    deepIntentAction = null
                )
            else -> null
        }
    }
}

data class OemGuidance(
    val brand: String,
    val instructions: String,
    val deepIntentAction: String?
)

/**
 * Full-screen onboarding dialog shown once on first launch.
 * Explains why battery exemption is needed and provides a one-tap fix.
 */
@Composable
fun BatteryOnboardingDialog(
    onDismiss: () -> Unit,
    onExemptionGranted: () -> Unit = {}
) {
    val context = LocalContext.current
    val deck = MaterialTheme.deckColors
    val oemGuidance = remember { BatteryOptimizationHelper.getOemGuidance() }
    var isExempt by remember { mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) }

    // Re-check exemption status when the dialog regains focus (user returns from settings).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val nowExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                isExempt = nowExempt
                if (nowExempt) {
                    BatteryOptimizationHelper.markOnboardingShown(context)
                    onExemptionGranted()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AlertDialog(
        onDismissRequest = {
            BatteryOptimizationHelper.markOnboardingShown(context)
            onDismiss()
        },
        containerColor = deck.surface,
        icon = {
            Icon(
                Icons.Default.BatteryAlert,
                contentDescription = null,
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(40.dp)
            )
        },
        title = {
            Text(
                text = "BACKGROUND RECEPTION",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column {
                Text(
                    text = "NXFR needs to run in the background to receive files when the app is closed. " +
                           "Without this, your device's power management may terminate the transfer service.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = deck.textPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isExempt) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1B5E20).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(12.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Battery optimization disabled — background reception active.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }

                // OEM-specific guidance.
                oemGuidance?.let { oem ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, deck.gridLineBright, RoundedCornerShape(4.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "${oem.brand} DETECTED",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            color = Color(0xFFFF9800)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "If the system dialog doesn't work, navigate manually:",
                            style = MaterialTheme.typography.bodySmall,
                            color = deck.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = oem.instructions,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = deck.textPrimary,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isExempt) {
                Button(
                    onClick = { BatteryOptimizationHelper.requestBatteryExemption(context) },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "ALLOW BACKGROUND",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                }
            } else {
                Button(
                    onClick = {
                        BatteryOptimizationHelper.markOnboardingShown(context)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "DONE",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        },
        dismissButton = {
            if (!isExempt) {
                TextButton(
                    onClick = {
                        BatteryOptimizationHelper.markOnboardingShown(context)
                        onDismiss()
                    }
                ) {
                    Text(
                        text = "SKIP",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = deck.textSecondary
                    )
                }
            }
        }
    )
}

/**
 * Persistent dismissible banner for the main screen.
 * Shows when battery optimization is active and the user skipped onboarding.
 */
@Composable
fun BatteryWarningBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val deck = MaterialTheme.deckColors
    var isExempt by remember { mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) }
    var isDismissed by remember { mutableStateOf(BatteryOptimizationHelper.isBannerDismissed(context)) }

    // Re-check on resume.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                // If user re-enabled optimization after dismissing, show banner again.
                if (!isExempt && isDismissed) {
                    // Keep dismissed — don't nag repeatedly. User made their choice.
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Don't show if exempted or dismissed.
    if (isExempt || isDismissed) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFE65100).copy(alpha = 0.12f), RoundedCornerShape(2.dp))
            .border(1.dp, Color(0xFFE65100).copy(alpha = 0.3f), RoundedCornerShape(2.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Default.BatteryAlert,
            contentDescription = null,
            tint = Color(0xFFFF9800),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Battery optimization may prevent file reception.",
            style = MaterialTheme.typography.bodySmall,
            color = deck.textPrimary,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = { BatteryOptimizationHelper.requestBatteryExemption(context) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "FIX",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = Color(0xFFFF9800)
            )
        }
        IconButton(
            onClick = {
                BatteryOptimizationHelper.dismissBanner(context)
                isDismissed = true
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = deck.textSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
