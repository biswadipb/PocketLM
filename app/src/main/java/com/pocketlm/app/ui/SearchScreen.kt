package com.pocketlm.app.ui

import android.app.ActivityManager
import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.DownloadState
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.HfFile
import com.pocketlm.app.data.HfModel
import com.pocketlm.app.data.MemoryEstimate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: PocketViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.search.collectAsStateWithLifecycle()
    val repoFiles by vm.repoFiles.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val installed by vm.models.collectAsStateWithLifecycle()
    val projectors by vm.projectors.collectAsStateWithLifecycle()
    val gen by vm.generationParams.collectAsStateWithLifecycle()
    val searchContext = LocalContext.current
    val memory = remember {
        ActivityManager.MemoryInfo().also {
            (searchContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
    }
    var query by rememberSaveable { mutableStateOf(state.query) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Search Hugging Face") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("llama, qwen, phi…") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { vm.searchHub(query) }, enabled = query.isNotBlank()) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.searchHub(query) }),
        )

        Box(Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                !state.searched -> Hint("Search for GGUF models. Smaller quantised files (Q4) run best on phones.")
                state.results.isEmpty() -> Hint("No models found.")
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.results, key = { it.id }) { model ->
                        val open = expanded == model.id
                        ModelCard(
                            model = model,
                            open = open,
                            files = repoFiles[model.id],
                            downloads = downloads,
                            installedNames = (installed + projectors).map { it.fileName }.toSet(),
                            fitFor = { file ->
                                if (file.isProjector) null
                                else MemoryEstimate.estimate(file.sizeBytes, null, gen.contextSize, memory.availMem, memory.totalMem)
                            },
                            onToggle = {
                                expanded = if (open) null else model.id
                                if (!open) vm.loadRepoFiles(model.id)
                            },
                            onDownload = { vm.downloadFromHub(model.id, it) },
                            onCancel = vm::cancelDownload,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelCard(
    model: HfModel,
    open: Boolean,
    files: com.pocketlm.app.RepoFiles?,
    downloads: Map<String, DownloadState>,
    installedNames: Set<String>,
    fitFor: (HfFile) -> MemoryEstimate.Result?,
    onToggle: () -> Unit,
    onDownload: (HfFile) -> Unit,
    onCancel: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
                Text(model.id, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${compact(model.downloads)} downloads · ${compact(model.likes)} likes",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (open) {
                Column(Modifier.padding(top = 8.dp)) {
                    when {
                        files == null || (files.files == null && files.error == null) ->
                            CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 2.dp)
                        files.error != null -> Text(files.error, color = MaterialTheme.colorScheme.error)
                        files.files.isNullOrEmpty() -> Text("No single-file GGUF models in this repository.")
                        else -> files.files.forEach { file ->
                            FileRow(
                                file = file,
                                installed = file.fileName in installedNames,
                                fit = fitFor(file),
                                progress = downloads[file.fileName],
                                onDownload = { onDownload(file) },
                                onCancel = { onCancel(file.fileName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: HfFile,
    installed: Boolean,
    fit: MemoryEstimate.Result?,
    progress: DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(file.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(if (file.isProjector) "Vision add-on" else file.quant, Formatter.formatShortFileSize(context, file.sizeBytes)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fit != null && fit.level != MemoryEstimate.Level.Comfortable) {
                    Text(
                        if (fit.level == MemoryEstimate.Level.TooBig) "Probably too big for this phone" else "Tight fit on this phone",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fit.level == MemoryEstimate.Level.TooBig) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            when {
                progress != null -> TextButton(onClick = onCancel) { Text("Cancel") }
                installed -> Text("Downloaded", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                else -> Button(onClick = onDownload) { Text("Download") }
            }
        }
        if (progress != null) {
            val p = progress.progress
            if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private fun compact(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}
