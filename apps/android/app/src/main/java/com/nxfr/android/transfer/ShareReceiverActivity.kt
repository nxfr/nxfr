package com.nxfr.android.transfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.nxfr.android.MainActivity
import com.nxfr.android.staging.StagedItem
import com.nxfr.android.staging.StagedType
import com.nxfr.android.staging.StagingRepository
import java.io.File
import java.util.UUID

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain" && intent.hasExtra(Intent.EXTRA_TEXT)) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        handleSharedText(text)
                    }
                } else {
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    uri?.let { handleSharedUris(listOf(it)) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                uris?.let { handleSharedUris(it) }
            }
        }
        
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
        finish()
    }
    
    private fun handleSharedUris(uris: List<Uri>) {
        var added = 0
        for (uri in uris) {
            val (name, size) = queryUriDetails(uri)
            val type = if (uri.toString().contains("image") || uri.toString().contains("video")) StagedType.MEDIA else StagedType.FILE
            val item = StagedItem(
                id = UUID.randomUUID().toString(),
                type = type,
                displayName = name,
                sizeBytes = size,
                uri = uri
            )
            if (StagingRepository.addItem(this, item)) {
                added++
            }
        }
        Toast.makeText(this, "Staged $added shared item${if (added != 1) "s" else ""} for NXFR send", Toast.LENGTH_SHORT).show()
    }

    private fun handleSharedText(text: String) {
        val textDir = File(cacheDir, "text")
        textDir.mkdirs()
        val textFile = File(textDir, "shared_text_${System.currentTimeMillis()}.txt")
        textFile.writeText(text)

        val item = StagedItem(
            id = UUID.randomUUID().toString(),
            type = StagedType.TEXT,
            displayName = textFile.name,
            sizeBytes = textFile.length(),
            localFile = textFile,
            mimeType = "text/plain"
        )
        StagingRepository.addItem(this, item)
        Toast.makeText(this, "Staged shared text for NXFR send", Toast.LENGTH_SHORT).show()
    }

    private fun queryUriDetails(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "shared_file"
        var size = 0L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (_: Throwable) {}
        return name to size
    }
}
