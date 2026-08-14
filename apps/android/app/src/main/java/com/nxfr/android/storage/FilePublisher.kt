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
                var displayName = name
                if (com.nxfr.android.prefs.NxfrPreferences.collisionRename.value) {
                    // Check if file already exists in public Downloads/NXFR
                    val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).resolve("NXFR")
                    if (publicDir.exists()) {
                        var target = File(publicDir, displayName)
                        var counter = 1
                        val dotIdx = name.lastIndexOf('.')
                        val base = if (dotIdx > 0) name.substring(0, dotIdx) else name
                        val ext = if (dotIdx > 0) name.substring(dotIdx) else ""
                        while (target.exists()) {
                            displayName = "$base ($counter)$ext"
                            target = File(publicDir, displayName)
                            counter++
                        }
                    }
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
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

                // Optionally copy to Gallery (Images / Video) if enabled
                if (com.nxfr.android.prefs.NxfrPreferences.saveToGallery.value) {
                    val lower = displayName.lowercase()
                    val isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".gif")
                    val isVideo = lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".3gp")

                    if (isImage || isVideo) {
                        try {
                            val galleryUri = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            val gValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            }
                            val gUri = resolver.insert(galleryUri, gValues)
                            if (gUri != null) {
                                resolver.openOutputStream(gUri)?.use { out ->
                                    inboxFile.inputStream().use { inp -> inp.copyTo(out) }
                                }
                                gValues.clear()
                                gValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                resolver.update(gUri, gValues, null, null)
                                logI("[publishToDownloads] Saved media to Gallery: gUri=$gUri")
                            }
                        } catch (e: Exception) {
                            logW("[publishToDownloads] Save to Gallery failed: ${e.message}")
                        }
                    }
                }

                // Successfully published via MediaStore -> delete inbox copy
                if (inboxFile.delete()) {
                    logI("[publishToDownloads] Inbox file deleted: ${inboxFile.absolutePath}")
                } else {
                    logW("[publishToDownloads] Could not delete inbox copy: ${inboxFile.absolutePath}")
                }

                val published = "Download/NXFR/$displayName"
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
