package com.nxfr.android.storage

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

object FilePublisher {
    private const val TAG = "FilePublisher"

    private fun logI(msg: String) { try { Log.i(TAG, msg) } catch (_: Throwable) {} }
    private fun logW(msg: String) { try { Log.w(TAG, msg) } catch (_: Throwable) {} }
    private fun logE(msg: String, t: Throwable? = null) { try { Log.e(TAG, msg, t) } catch (_: Throwable) {} }

    /**
     * Publishes a file from app inbox to public Downloads/NXFR.
     * T1 — Log EVERY step clearly.
     * T2 — MediaStore primary path -> File API Fallback #2 -> App Inbox Fallback #3.
     */
    fun publishToDownloads(
        context: Context?,
        inboxFile: File,
        targetFallbackDir: File? = null,
        mediaStorePublisher: ((File) -> String)? = null
    ): String {
        val size = inboxFile.length()
        val name = inboxFile.name
        logI("[publishToDownloads] Starting publish: $name ($size bytes) from ${inboxFile.absolutePath}")

        if (!inboxFile.exists()) {
            logE("[publishToDownloads] ERROR: Inbox file does not exist: ${inboxFile.absolutePath}")
            return inboxFile.absolutePath
        }

        // Allow custom publisher for testing or primary MediaStore path
        if (mediaStorePublisher != null) {
            try {
                return mediaStorePublisher(inboxFile)
            } catch (e: Exception) {
                logE("[publishToDownloads] Custom MediaStore publisher failed for $name: ${e.message}", e)
            }
        } else {
            // --- PATH 1: MediaStore.Downloads ---
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/NXFR")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context?.contentResolver ?: throw Exception("Null Context")
                val uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw Exception("MediaStore insert returned null URI")

                logI("[publishToDownloads] MediaStore insert succeeded: uri=$uri")

                var bytesWritten = 0L
                resolver.openOutputStream(uri)?.use { out ->
                    inboxFile.inputStream().use { inp ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        while (inp.read(buffer).also { bytesRead = it } >= 0) {
                            out.write(buffer, 0, bytesRead)
                            bytesWritten += bytesRead
                        }
                        out.flush()
                    }
                } ?: throw Exception("Failed to open MediaStore output stream for uri=$uri")

                logI("[publishToDownloads] Stream copy finished: $bytesWritten bytes written to uri=$uri")

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                val rows = resolver.update(uri, values, null, null)
                logI("[publishToDownloads] IS_PENDING cleared ($rows rows updated): uri=$uri")

                // Successfully published via MediaStore -> delete inbox copy
                if (inboxFile.delete()) {
                    logI("[publishToDownloads] Inbox file deleted: ${inboxFile.absolutePath}")
                } else {
                    logW("[publishToDownloads] Could not delete inbox copy: ${inboxFile.absolutePath}")
                }

                val published = "Download/NXFR/$name"
                logI("[publishToDownloads] Published to: $published")
                return published
            } catch (e: Exception) {
                logE("[publishToDownloads] MediaStore publish failed for $name: ${e.message}", e)
            }
        }

        // --- PATH 2: File API Fallback #2 (public Downloads/NXFR) ---
        try {
            logI("[publishToDownloads] Attempting Fallback #2 (File API to public Downloads/NXFR)...")
            val publicDownloadsDir = targetFallbackDir ?: try {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).resolve("NXFR")
            } catch (_: Throwable) {
                File(System.getProperty("java.io.tmpdir"), "Downloads/NXFR")
            }
            if (!publicDownloadsDir.exists()) {
                val created = publicDownloadsDir.mkdirs()
                logI("[publishToDownloads] Created public dir ${publicDownloadsDir.absolutePath}: $created")
            }
            val destFile = File(publicDownloadsDir, name)
            inboxFile.copyTo(destFile, overwrite = true)
            logI("[publishToDownloads] Fallback #2 File API copy complete (${destFile.length()} bytes): ${destFile.absolutePath}")
            inboxFile.delete()
            return destFile.absolutePath
        } catch (e2: Exception) {
            logE("[publishToDownloads] Fallback #2 File API copy failed for $name: ${e2.message}", e2)
        }

        // --- PATH 3: Inbox Fallback #3 ---
        logW("[publishToDownloads] Fallback: kept in inbox/${inboxFile.name} (${inboxFile.absolutePath})")
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "File saved to app inbox: $name", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {}

        return inboxFile.absolutePath
    }
}
