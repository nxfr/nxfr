package com.nxfr.android.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nxfr.android.staging.StagedItem
import com.nxfr.android.staging.StagedType
import com.nxfr.android.staging.StagingRepository
import java.io.File
import java.util.UUID

@Composable
fun TextComposeDialog(
    onDismiss: () -> Unit,
    onStaged: () -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("note.txt") }
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compose Text Snippet") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Filename") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Text content") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (content.isNotBlank()) {
                        val textDir = File(context.cacheDir, "text")
                        textDir.mkdirs()
                        val fileName = if (title.endsWith(".txt", ignoreCase = true)) title else "$title.txt"
                        val cleanName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val textFile = File(textDir, cleanName)
                        textFile.writeText(content)

                        StagingRepository.addItem(
                            context,
                            StagedItem(
                                id = UUID.randomUUID().toString(),
                                type = StagedType.TEXT,
                                displayName = cleanName,
                                sizeBytes = textFile.length(),
                                localFile = textFile,
                                mimeType = "text/plain"
                            )
                        )
                        onStaged()
                    }
                    onDismiss()
                },
                enabled = content.isNotBlank()
            ) {
                Text("Stage Text")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
