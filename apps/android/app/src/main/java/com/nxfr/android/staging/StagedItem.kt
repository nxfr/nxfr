package com.nxfr.android.staging

import android.net.Uri
import java.io.File

enum class StagedType {
    FILE, MEDIA, TEXT, FOLDER, APP, CONTACT
}

data class StagedItem(
    val id: String,
    val type: StagedType,
    val displayName: String,
    val sizeBytes: Long,
    val uri: Uri? = null,
    val localFile: File? = null,
    val isFolder: Boolean = false,
    val fileCount: Int? = null,
    val mimeType: String? = null
)
