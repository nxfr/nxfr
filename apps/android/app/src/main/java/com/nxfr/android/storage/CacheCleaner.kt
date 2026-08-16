package com.nxfr.android.storage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Utility to sweep and purge all temporary staging files and orphaned caches
 * from [Context.getCacheDir].
 */
object CacheCleaner {
    private const val TAG = "CacheCleaner"

    /**
     * Purge all temporary staging directories, send copies, contact exports,
     * APK extracts, and debug bundles from [Context.getCacheDir].
     */
    suspend fun cleanAllStaleCache(context: Context) = withContext(Dispatchers.IO) {
        try {
            val cache = context.cacheDir ?: return@withContext
            var bytesFreed = 0L
            var filesDeleted = 0

            cache.listFiles()?.forEach { file ->
                val name = file.name
                val isStagingDir = file.isDirectory && (
                    name.startsWith("staging_") ||
                    name == "web-share-staging" ||
                    name == "apps" ||
                    name == "contacts" ||
                    name == "nxfr_contacts" ||
                    name == "text" ||
                    name == "media" ||
                    name == "nxfr_paste" ||
                    name.startsWith("debug_bundle_")
                )

                val isStagingFile = file.isFile && (
                    name.startsWith("send_") ||
                    name.endsWith(".tmp") ||
                    name.startsWith("nxfr_debug_bundle_")
                )

                if (isStagingDir || isStagingFile) {
                    val size = calculateSize(file)
                    if (file.deleteRecursively()) {
                        bytesFreed += size
                        filesDeleted++
                    }
                }
            }

            if (bytesFreed > 0) {
                Log.i(TAG, "Cache cleanup completed: deleted $filesDeleted items, freed ${bytesFreed / (1024 * 1024)} MB")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error cleaning cache: ${e.message}")
        }
    }

    /** Synchronous version safe for Application.onCreate or onDispose */
    fun cleanAllStaleCacheSync(context: Context) {
        try {
            val cache = context.cacheDir ?: return
            cache.listFiles()?.forEach { file ->
                val name = file.name
                val isStagingDir = file.isDirectory && (
                    name.startsWith("staging_") ||
                    name == "web-share-staging" ||
                    name == "apps" ||
                    name == "contacts" ||
                    name == "nxfr_contacts" ||
                    name == "text" ||
                    name == "media" ||
                    name == "nxfr_paste" ||
                    name.startsWith("debug_bundle_")
                )

                val isStagingFile = file.isFile && (
                    name.startsWith("send_") ||
                    name.endsWith(".tmp") ||
                    name.startsWith("nxfr_debug_bundle_")
                )

                if (isStagingDir || isStagingFile) {
                    file.deleteRecursively()
                }
            }
        } catch (_: Throwable) {}
    }

    private fun calculateSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var size = 0L
        file.listFiles()?.forEach { child ->
            size += calculateSize(child)
        }
        return size
    }
}
