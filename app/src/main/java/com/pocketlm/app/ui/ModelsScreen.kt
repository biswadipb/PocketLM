package com.pocketlm.app.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.CatalogModel
import com.pocketlm.app.data.LocalModel
import com.pocketlm.app.data.ModelCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    vm: PocketViewModel,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onModelLoaded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val models by vm.models.collectAsStateWithLifecycle()
    val loaded by vm.loadedModel.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::import)
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Models") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("On this device") }
            if (models.isEmpty()) {
                item { Text("Nothing yet. Download a model below or import a .gguf file.") }
            }
            items(models, key = { it.path }) { m ->
                LocalModelCard(
                    model = m,
                    sizeText = Formatter.formatShortFileSize(context, m.sizeBytes),
                    isLoaded = loaded?.path == m.path,
                    busy = loading,
                    onLoad = { vm.load(m); onModelLoaded() },
                    onUnload = vm::unload,
                    onDelete = { vm.delete(m) },
                )
            }
            item {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null)
                    Text("  Import .gguf from storage")
                }
            }

            item { SectionTitle("Download") }
            items(ModelCatalog.models, key = { it.fileName }) { c ->
                CatalogCard(
                    model = c,
                    installed = models.any { it.fileName == c.fileName },
                    progress = downloads[c.fileName],
                    onDownload = { vm.download(c) },
                    onCancel = { vm.cancelDownload(c.fileName) },
                )
            }

            item {
                OutlinedButton(onClick = onOpenSearch, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Text("  Search Hugging Face")
                }
            }

            item { SectionTitle("From a link") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("https://…/model.gguf") },
                        singleLine = true,
                    )
                    IconButton(
                        onClick = { vm.downloadFromUrl(url.trim()); url = "" },
                        enabled = url.isNotBlank(),
                    ) { Icon(Icons.Filled.Download, contentDescription = "Download") }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun LocalModelCard(
    model: LocalModel,
    sizeText: String,
    isLoaded: Boolean,
    busy: Boolean,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(model.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            Text(sizeText, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isLoaded) {
                    Button(onClick = onUnload) { Text("Unload") }
                } else {
                    Button(onClick = onLoad, enabled = !busy) { Text("Load") }
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text(" Delete")
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(
    model: CatalogModel,
    installed: Boolean,
    progress: com.pocketlm.app.DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(model.name, style = MaterialTheme.typography.titleSmall)
            Text("${model.sizeMb} MB · ${model.description}", style = MaterialTheme.typography.bodySmall)
            when {
                progress != null -> {
                    if (progress.progress != null) {
                        LinearProgressIndicator(progress = { progress.progress }, Modifier.fillMaxWidth().padding(top = 8.dp))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                installed -> Text("Downloaded", Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.primary)
                else -> Button(onClick = onDownload, Modifier.padding(top = 8.dp)) { Text("Download") }
            }
        }
    }
}
