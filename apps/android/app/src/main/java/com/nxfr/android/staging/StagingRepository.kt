package com.nxfr.android.staging

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object StagingRepository {
    private const val TAG = "StagingRepository"

    private val _stagedItems = MutableStateFlow<List<StagedItem>>(emptyList())
    val stagedItems: StateFlow<List<StagedItem>> = _stagedItems.asStateFlow()

    fun addItem(context: Context, item: StagedItem): Boolean {
        val current = _stagedItems.value
        val isDuplicate = current.any { existing ->
            (item.uri != null && existing.uri == item.uri) ||
            (item.localFile != null && existing.localFile?.absolutePath == item.localFile.absolutePath) ||
            (existing.displayName == item.displayName && existing.sizeBytes == item.sizeBytes)
        }
        if (isDuplicate) {
            try { Toast.makeText(context, "Already in selection", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
            return false
        }
        _stagedItems.value = current + item
        return true
    }

    fun addItems(context: Context, newItems: List<StagedItem>): Int {
        var addedCount = 0
        for (item in newItems) {
            if (addItem(context, item)) {
                addedCount++
            }
        }
        return addedCount
    }

    fun removeItem(id: String) {
        _stagedItems.value = _stagedItems.value.filter { it.id != id }
    }

    fun clear() {
        _stagedItems.value = emptyList()
    }

    fun calculateTotalSize(): Long {
        return _stagedItems.value.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    }

    fun calculateTotalFiles(): Int {
        return _stagedItems.value.sumOf { item ->
            if (item.isFolder && item.fileCount != null) {
                item.fileCount
            } else {
                1
            }
        }
    }

    suspend fun prepareStagingDirectory(context: Context): File = withContext(Dispatchers.IO) {
        val items = _stagedItems.value
        if (items.isEmpty()) {
            throw IllegalStateException("No items selected for transfer")
        }
        val ts = System.currentTimeMillis()
        val stagingDir = File(context.cacheDir, "staging_$ts")
        stagingDir.mkdirs()

        if (items.size == 1 && !items[0].isFolder) {
            val item = items[0]
            val dest = File(stagingDir, item.displayName)
            when {
                item.localFile != null && item.localFile.exists() -> {
                    item.localFile.copyTo(dest, overwrite = true)
                }
                item.uri != null -> {
                    try {
                        context.contentResolver.openInputStream(item.uri)?.use { input ->
                            dest.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Permission denied for ${item.displayName}: ${e.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Permission denied for ${item.displayName}", Toast.LENGTH_SHORT).show()
                        }
                        // Single file — nothing to return.
                        stagingDir.deleteRecursively()
                        throw IllegalStateException("Permission denied for ${item.displayName}")
                    }
                }
            }
            return@withContext dest
        }

        for (item in items) {
            when {
                item.localFile != null && item.localFile.exists() -> {
                    val dest = File(stagingDir, item.displayName)
                    if (item.localFile.isDirectory) {
                        item.localFile.copyRecursively(dest, overwrite = true)
                    } else {
                        item.localFile.copyTo(dest, overwrite = true)
                    }
                }
                item.isFolder && item.uri != null -> {
                    try {
                        val folderDir = File(stagingDir, item.displayName)
                        folderDir.mkdirs()
                        val treeDoc = DocumentFile.fromTreeUri(context, item.uri)
                        if (treeDoc != null && treeDoc.isDirectory) {
                            copyDocumentTree(context, treeDoc, folderDir)
                        }
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Permission denied for folder ${item.displayName}: ${e.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Permission denied for ${item.displayName}", Toast.LENGTH_SHORT).show()
                        }
                        // Skip this folder, continue with remaining items.
                    }
                }
                item.uri != null -> {
                    try {
                        val dest = File(stagingDir, item.displayName)
                        context.contentResolver.openInputStream(item.uri)?.use { input ->
                            dest.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Permission denied for ${item.displayName}: ${e.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Permission denied for ${item.displayName}", Toast.LENGTH_SHORT).show()
                        }
                        // Skip this file, continue with remaining items.
                    }
                }
            }
        }

        val stagedFiles = stagingDir.listFiles()
        if (stagedFiles == null || stagedFiles.isEmpty()) {
            stagingDir.deleteRecursively()
            throw IllegalStateException("No files could be read from selected items")
        }

        stagingDir
    }

    private fun copyDocumentTree(context: Context, doc: DocumentFile, targetDir: File) {
        doc.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            val childFile = File(targetDir, name)
            if (child.isDirectory) {
                childFile.mkdirs()
                copyDocumentTree(context, child, childFile)
            } else if (child.isFile) {
                try {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        childFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Permission denied for $name: ${e.message}")
                    // Skip this child file, continue copying remaining tree.
                }
            }
        }
    }

    fun cleanStagingCache(context: Context) {
        com.nxfr.android.storage.CacheCleaner.cleanAllStaleCacheSync(context)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
