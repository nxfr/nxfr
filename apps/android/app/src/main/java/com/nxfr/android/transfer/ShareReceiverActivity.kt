package com.nxfr.android.transfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.nxfr.android.MainActivity
import com.nxfr.android.service.NxfrService

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                uri?.let { handleSharedUris(listOf(it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                uris?.let { handleSharedUris(it) }
            }
        }
        
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
    
    private fun handleSharedUris(uris: List<Uri>) {
        Toast.makeText(this, "Received ${uris.size} files to share via NXFR", Toast.LENGTH_SHORT).show()
        // Copy URIs to a cache path so FFI can access them by path.
        for (uri in uris) {
            val inputStream = contentResolver.openInputStream(uri) ?: continue
            val fileName = uri.lastPathSegment ?: "shared_file"
            val cacheFile = cacheDir.resolve(fileName)
            cacheFile.outputStream().use { out -> inputStream.copyTo(out) }
            inputStream.close()

            // Start the service to send the file.
            // The user needs to select a device first — launch MainActivity
            // which will navigate to device selection.
            val serviceIntent = Intent(this, NxfrService::class.java).apply {
                action = NxfrService.ACTION_SEND
                putExtra(NxfrService.EXTRA_FILE_PATH, cacheFile.absolutePath)
                // EXTRA_ADDR is set by the UI when the user picks a device.
            }
            // For Phase 7, we don't start the service here — the UI flow
            // handles device selection first, then triggers the send.
            // This just copies files to cache for later use.
        }
    }
}
