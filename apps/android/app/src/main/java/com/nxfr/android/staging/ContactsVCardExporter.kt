package com.nxfr.android.staging

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.InputStream

object ContactsVCardExporter {
    private const val TAG = "ContactsVCardExporter"

    data class ExportResult(
        val file: File? = null,
        val displayName: String? = null,
        val needsPermission: Boolean = false,
        val error: String? = null
    )

    fun exportContact(context: Context, contactUri: Uri): ExportResult {
        var lookupKey: String? = null
        var displayName: String? = null

        try {
            context.contentResolver.query(
                contactUri,
                arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val keyIdx = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    if (keyIdx != -1) lookupKey = cursor.getString(keyIdx)
                    if (nameIdx != -1) displayName = cursor.getString(nameIdx)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to query contact lookup key: ${e.message}", e)
        }

        val effectiveName = displayName?.takeIf { it.isNotBlank() } ?: "Contact"
        val sanitizedName = effectiveName.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim()

        var inputStream: InputStream? = null
        var lastException: Throwable? = null

        // Step b & c: Attempt 1 - CONTENT_VCARD_URI
        if (lookupKey != null) {
            try {
                val vCardUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)
                inputStream = context.contentResolver.openInputStream(vCardUri)
                    ?: context.contentResolver.openAssetFileDescriptor(vCardUri, "r")?.createInputStream()
            } catch (e: SecurityException) {
                lastException = e
                Log.e(TAG, "SecurityException on CONTENT_VCARD_URI: ${e.message}", e)
            } catch (e: Throwable) {
                lastException = e
                Log.e(TAG, "Error opening CONTENT_VCARD_URI: ${e.message}", e)
            }
        }

        // Attempt 2 - openTypedAssetFileDescriptor on CONTENT_LOOKUP_URI with text/x-vcard
        if (inputStream == null && lookupKey != null) {
            try {
                val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
                inputStream = context.contentResolver.openTypedAssetFileDescriptor(lookupUri, "text/x-vcard", null)?.createInputStream()
            } catch (e: SecurityException) {
                lastException = e
                Log.e(TAG, "SecurityException on openTypedAssetFileDescriptor: ${e.message}", e)
            } catch (e: Throwable) {
                lastException = e
                Log.e(TAG, "Error on openTypedAssetFileDescriptor: ${e.message}", e)
            }
        }

        // Attempt 3 - fallback on picked Uri directly
        if (inputStream == null) {
            try {
                inputStream = context.contentResolver.openInputStream(contactUri)
            } catch (e: SecurityException) {
                lastException = e
                Log.e(TAG, "SecurityException on direct contactUri: ${e.message}", e)
            } catch (e: Throwable) {
                lastException = e
                Log.e(TAG, "Error on direct contactUri: ${e.message}", e)
            }
        }

        // If permission error and permission not granted:
        if (inputStream == null && lastException is SecurityException) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                return ExportResult(
                    displayName = effectiveName,
                    needsPermission = true,
                    error = "Permission needed to export contact"
                )
            }
        }

        if (inputStream == null) {
            val err = "Failed to open vCard stream: ${lastException?.message ?: "Unknown error"}"
            Log.e(TAG, err, lastException)
            return ExportResult(displayName = effectiveName, error = err)
        }

        return try {
            val cacheDir = File(context.cacheDir, "nxfr_contacts").apply { mkdirs() }
            val vcfFile = File(cacheDir, "$sanitizedName.vcf")

            inputStream.use { input ->
                vcfFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!vcfFile.exists() || vcfFile.length() == 0L) {
                val err = "Generated vCard file is empty (0 bytes)"
                Log.e(TAG, err)
                return ExportResult(displayName = effectiveName, error = err)
            }

            val headerCheck = vcfFile.bufferedReader().use { reader ->
                val lines = mutableListOf<String>()
                repeat(10) {
                    val line = reader.readLine() ?: return@repeat
                    lines.add(line)
                }
                lines.any { it.contains("BEGIN:VCARD", ignoreCase = true) }
            }

            if (!headerCheck) {
                val err = "Exported file does not contain BEGIN:VCARD header"
                Log.e(TAG, err)
                return ExportResult(displayName = effectiveName, error = err)
            }

            ExportResult(file = vcfFile, displayName = "$sanitizedName.vcf")
        } catch (e: Throwable) {
            Log.e(TAG, "Error writing vCard file: ${e.message}", e)
            ExportResult(displayName = effectiveName, error = e.message)
        }
    }
}
