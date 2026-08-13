package com.nxfr.android.ui.sheets

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.nxfr.android.staging.StagedItem
import com.nxfr.android.staging.StagedType
import com.nxfr.android.staging.StagingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class AppItemModel(
    val label: String,
    val packageName: String,
    val apkFile: File,
    val sizeBytes: Long,
    val icon: Drawable
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledAppsSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var appsList by remember { mutableStateOf<List<AppItemModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val models = installedApps.mapNotNull { appInfo ->
                try {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val apkFile = File(appInfo.sourceDir)
                    if (!apkFile.exists()) return@mapNotNull null
                    val size = apkFile.length()
                    val icon = pm.getApplicationIcon(appInfo)
                    AppItemModel(
                        label = label,
                        packageName = appInfo.packageName,
                        apkFile = apkFile,
                        sizeBytes = size,
                        icon = icon
                    )
                } catch (_: Throwable) {
                    null
                }
            }.sortedBy { it.label.lowercase() }
            appsList = models
            isLoading = false
        }
    }

    val filteredApps = remember(searchQuery, appsList) {
        if (searchQuery.isBlank()) {
            appsList
        } else {
            appsList.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Select App to Share",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search installed apps") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val appsDir = File(context.cacheDir, "apps")
                                        appsDir.mkdirs()
                                        val cleanName = app.label.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                        val stagedApk = File(appsDir, "$cleanName.apk")
                                        app.apkFile.copyTo(stagedApk, overwrite = true)

                                        withContext(Dispatchers.Main) {
                                            StagingRepository.addItem(
                                                context,
                                                StagedItem(
                                                    id = UUID.randomUUID().toString(),
                                                    type = StagedType.APP,
                                                    displayName = "$cleanName.apk",
                                                    sizeBytes = app.sizeBytes,
                                                    localFile = stagedApk,
                                                    mimeType = "application/vnd.android.package-archive"
                                                )
                                            )
                                            onDismiss()
                                        }
                                    }
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Image(
                                bitmap = app.icon.toBitmap(width = 48, height = 48).asImageBitmap(),
                                contentDescription = app.label,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${StagingRepository.formatBytes(app.sizeBytes)} • ${app.packageName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
