package com.unifiedart.app

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.unifiedart.app.data.*

@Composable
fun DownloadsPanel(model: GalleryViewModel) {
    val tasks by model.downloads.collectAsState()
    val context = LocalContext.current
    var directory by remember { mutableStateOf(model.local.downloadDirectory) }
    var error by remember { mutableStateOf("") }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                model.local.downloadDirectory = uri.toString(); directory = uri.toString(); error = ""
            } catch (_: SecurityException) { error = "This folder couldn't grant persistent access. Choose another folder." }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Downloads", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { folderPicker.launch(null) }) { Text(if (directory.isEmpty()) "Choose download folder" else "Change download folder") }
            Text(if (directory.isEmpty()) "Select a folder first, then use Download in the viewer or hold thumbnails to select a batch." else "Original files save to your chosen folder. Hold thumbnails to select a batch.", style = MaterialTheme.typography.bodySmall)
            if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow download notifications") }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
        items(tasks, key = { it.id }) { task ->
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(task.label, style = MaterialTheme.typography.titleSmall)
                    Text("${task.state} · ${task.done}/${task.total} files · ${task.bytes / 1024} KB")
                    if (task.state == "Downloading") LinearProgressIndicator(progress = { if (task.total > 0) task.done.toFloat() / task.total else 0f }, modifier = Modifier.fillMaxWidth())
                    if (task.message.isNotBlank()) Text(task.message, style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (task.state in listOf("Queued", "Downloading")) TextButton(onClick = { model.cancelDownload(task.id) }) { Text("Cancel") }
                        if (task.state in listOf("Failed", "Canceled", "Completed with skips")) TextButton(onClick = { model.retryDownload(task) }) { Text("Retry") }
                    }
                }
            }
        }
    }
}
