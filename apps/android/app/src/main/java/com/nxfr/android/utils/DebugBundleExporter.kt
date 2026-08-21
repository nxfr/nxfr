package com.nxfr.android.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.nxfr.android.prefs.NxfrPreferences
import com.nxfr.android.service.NxfrService
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DebugBundleExporter {
    fun exportDebugBundle(context: Context) {
        try {
            val bundleDir = File(context.cacheDir, "debug_bundle_${System.currentTimeMillis()}")
            bundleDir.mkdirs()

            // 1. Filtered logcat
            val logcatFile = File(bundleDir, "logcat.txt")
            try {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "2000"))
                val text = process.inputStream.bufferedReader().use { it.readText() }
                val filteredLines = text.lines().filter { line ->
                    line.contains("nxfr", ignoreCase = true) || line.contains("Nxfr", ignoreCase = true)
                }
                logcatFile.writeText(if (filteredLines.isNotEmpty()) filteredLines.joinToString("\n") else text)
            } catch (e: Exception) {
                logcatFile.writeText("Logcat fetch error: ${e.message}")
            }

            // 2. Environment Info
            val envFile = File(bundleDir, "environment.json")
            val envObj = JSONObject().apply {
                put("app_version", com.nxfr.android.BuildConfig.VERSION_NAME)
                put("version_code", com.nxfr.android.BuildConfig.VERSION_CODE)
                put("os_version", Build.VERSION.RELEASE)
                put("sdk_int", Build.VERSION.SDK_INT)
                put("device_model", Build.MODEL)
                put("device_manufacturer", Build.MANUFACTURER)
                put("arch", Build.SUPPORTED_ABIS.joinToString(","))
            }
            envFile.writeText(envObj.toString(2))

            // 3. Settings JSON (sanitized — NO tokens, NO keys, NO certs)
            val settingsFile = File(bundleDir, "settings.json")
            val settingsObj = JSONObject().apply {
                put("save_to_gallery", NxfrPreferences.saveToGallery.value)
                put("save_to_history", NxfrPreferences.saveToHistory.value)
                put("auto_finish", NxfrPreferences.autoFinish.value)
                put("collision_rename", NxfrPreferences.collisionRename.value)
                put("default_send_mode", NxfrPreferences.defaultSendMode.value)
                put("show_checksum", NxfrPreferences.showChecksum.value)
                put("advertise_mode", NxfrPreferences.advertiseMode.value)
                put("port", NxfrPreferences.port.value)
                put("discovery_timeout_ms", NxfrPreferences.discoveryTimeoutMs.value)
                put("multicast_address", NxfrPreferences.multicastAddress.value)
                put("is_listening", NxfrService.isListening.value)
            }
            settingsFile.writeText(settingsObj.toString(2))

            // 4. Paired count
            val pairedFile = File(bundleDir, "paired_count.json")
            var count = 0
            try {
                val storeDir = context.filesDir.absolutePath
                val jsonStr = NxfrService.NxfrBridge.nxfr_paired_list(storeDir)
                val json = JSONObject(jsonStr)
                if (json.has("devices")) {
                    count = json.getJSONArray("devices").length()
                }
            } catch (_: Exception) {}
            pairedFile.writeText("{\"paired_device_count\": $count}")

            // 5. Create Zip File
            val zipFile = File(context.cacheDir, "nxfr_debug_bundle_${System.currentTimeMillis()}.zip")
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                bundleDir.listFiles()?.forEach { f ->
                    zos.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { input -> input.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            bundleDir.deleteRecursively()

            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Debug Bundle"))
        } catch (e: Exception) {
            Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
