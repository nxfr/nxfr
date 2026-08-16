package com.nxfr.android.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.nxfr.android.service.NxfrService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Diagnostic bundle exporter (ZIP with sanitized system/app state).
 */
object DiagnosticBundleExporter {

    private const val TAG = "DiagBundle"
    private const val MAX_LOGCAT_LINES = 500

    /**
     * Build the ZIP, save it, and return the File.
     * Returns null on failure.
     */
    fun export(context: Context): File? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val zipFile = File(context.cacheDir, "nxfr-debug-bundle-$timestamp.zip")

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                writeEntry(zos, "manifest.json", buildManifest(context, timestamp))
                writeEntry(zos, "system/device.json", buildDeviceInfo())
                writeEntry(zos, "system/permissions.json", buildPermissions(context))
                writeEntry(zos, "nxfr/config.json", buildConfig(context))
                writeEntry(zos, "nxfr/identity.json", buildIdentity(context))
                writeEntry(zos, "nxfr/paired_devices.json", buildPairedDevices(context))
                writeEntry(zos, "logs/logcat.log", captureLogcat())
            }

            Log.i(TAG, "Diagnostic bundle created: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
            zipFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create diagnostic bundle", e)
            null
        }
    }

    /**
     * Export and immediately open the system share sheet.
     */
    fun exportAndShare(context: Context) {
        val zipFile = export(context) ?: return
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "NXFR Diagnostic Bundle")
                putExtra(Intent.EXTRA_TEXT, "NXFR diagnostic bundle attached. Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share diagnostic bundle").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share diagnostic bundle", e)
        }
    }

    // ── Section builders ─────────────────────────────────────────────

    private fun buildManifest(context: Context, timestamp: String): String {
        val pm = context.packageManager
        val pi = try {
            pm.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) { null }

        return JSONObject().apply {
            put("bundle_version", 1)
            put("timestamp", timestamp)
            put("app_version_name", pi?.versionName ?: "unknown")
            put("app_version_code", pi?.longVersionCode ?: -1)
            put("package", context.packageName)
        }.toString(2)
    }

    private fun buildDeviceInfo(): String {
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("android_version", Build.VERSION.RELEASE)
            put("api_level", Build.VERSION.SDK_INT)
            put("build_fingerprint", Build.FINGERPRINT)
            put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("total_ram_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024))
        }.toString(2)
    }

    private fun buildPermissions(context: Context): String {
        val pm = context.packageManager
        val pi = try {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        } catch (_: Exception) { return "{}" }

        val granted = mutableListOf<String>()
        val denied = mutableListOf<String>()

        pi.requestedPermissions?.forEachIndexed { i, perm ->
            val flags = pi.requestedPermissionsFlags?.get(i) ?: 0
            val shortName = perm.substringAfterLast('.')
            if (flags and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) {
                granted.add(shortName)
            } else {
                denied.add(shortName)
            }
        }

        return JSONObject().apply {
            put("granted", JSONArray(granted))
            put("denied", JSONArray(denied))
        }.toString(2)
    }

    private fun buildConfig(context: Context): String {
        val prefs = context.getSharedPreferences("nxfr_prefs", Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("auto_accept_global", prefs.getInt("auto_accept_global", 0))
            put("battery_banner_dismissed", prefs.getBoolean("battery_banner_dismissed", false))
            put("battery_onboarding_shown", prefs.getBoolean("battery_onboarding_shown", false))
            put("battery_exempt", com.nxfr.android.battery.BatteryOptimizationHelper
                .isIgnoringBatteryOptimizations(context))
        }.toString(2)
    }

    private fun buildIdentity(context: Context): String {
        return try {
            val storeDir = NxfrService.getIdentityDir(context)
            val initJson = NxfrService.NxfrBridge.nxfr_identity_load(storeDir)
            val json = JSONObject(initJson)
            // Only expose the public device_id — NEVER private key material.
            val fullId = if (json.has("device_id")) json.getString("device_id") else "unknown"
            JSONObject().apply {
                put("device_id_short", if (fullId.length > 12) fullId.take(12) + "..." else fullId)
            }.toString(2)
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "unknown").toString(2)
        }
    }

    private fun buildPairedDevices(context: Context): String {
        return try {
            val storeDir = NxfrService.getIdentityDir(context)
            val listJson = NxfrService.NxfrBridge.nxfr_paired_list(storeDir)
            val json = JSONObject(listJson)

            if (json.has("error")) {
                return json.toString(2)
            }

            val devices = json.optJSONArray("devices") ?: JSONArray()
            val sanitized = JSONArray()
            for (i in 0 until devices.length()) {
                val dev = devices.getJSONObject(i)
                sanitized.put(JSONObject().apply {
                    // Truncate device_id to first 8 chars for privacy.
                    val id = dev.optString("device_id", "")
                    put("device_id_short", if (id.length > 8) id.take(8) + "..." else id)
                    put("name", dev.optString("name", "unknown"))
                    put("trust_level", dev.optString("trust_level", "unknown"))
                    put("auto_accept", dev.optString("auto_accept", "prompt"))
                    // Don't include SPKI, cert data, or full device_id.
                })
            }

            JSONObject().apply {
                put("count", devices.length())
                put("devices", sanitized)
            }.toString(2)
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "unknown").toString(2)
        }
    }

    private fun captureLogcat(): String {
        return try {
            val pid = android.os.Process.myPid()
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", MAX_LOGCAT_LINES.toString(), "--pid=$pid")
            )
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            reader.forEachLine { line ->
                // Sanitize: redact anything that looks like a token or key.
                val sanitized = line
                    .replace(Regex("token[=:]\\s*\\S+", RegexOption.IGNORE_CASE), "token=<REDACTED>")
                    .replace(Regex("key[=:]\\s*[A-Fa-f0-9]{16,}"), "key=<REDACTED>")
                    .replace(Regex("sas[=:]\\s*\\d{6}", RegexOption.IGNORE_CASE), "sas=<REDACTED>")
                sb.appendLine(sanitized)
            }
            process.waitFor()
            sb.toString()
        } catch (e: Exception) {
            "Failed to capture logcat: ${e.message}"
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun writeEntry(zos: ZipOutputStream, path: String, content: String) {
        zos.putNextEntry(ZipEntry(path))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
}
