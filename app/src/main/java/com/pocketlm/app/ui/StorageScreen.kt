package com.pocketlm.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(vm: PocketViewModel, onBack: () -> Unit, onOpenModels: () -> Unit, modifier: Modifier = Modifier) {
    val report by vm.storage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshStorage() }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all chats?") },
            text = { Text("This removes every saved conversation with its photos and attached files. Downloaded models are kept.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteAllChats() }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Storage") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            val r = report
            if (r == null) {
                Text("Measuring…")
            } else {
                fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
                Text("PocketLM uses ${size(r.totalBytes)}", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.padding(top = 12.dp)) {
                    UsageRow("Models and add-ons", size(r.modelsBytes))
                    UsageRow("Chats", size(r.chatsBytes))
                    UsageRow("Photos", size(r.imagesBytes))
                    UsageRow("Attached documents", size(r.documentsBytes))
                    UsageRow("Cache", size(r.cacheBytes))
                }
                OutlinedButton(onClick = onOpenModels, Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text("Manage models")
                }
                Button(onClick = vm::cleanUpStorage, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        if (r.unusedCount > 0) "Clean up (${size(r.unusedBytes)} unused)" else "Clear cache",
                    )
                }
                Text(
                    "Clean up clears the cache and removes photos and documents that no chat uses any more.",
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                ) { Text("Delete all chats", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.primary)
    }
}
