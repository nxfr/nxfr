package com.nxfr.android.transfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.nxfr.android.MainActivity

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
        Toast.makeText(this, "Received ${uris.size} files to share", Toast.LENGTH_SHORT).show()
        // TODO: Start NxfrService to send files
    }
}
