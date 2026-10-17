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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.pocketlm.app.data.MemoryEstimate
import com.pocketlm.app.data.Recommendation
import com.pocketlm.app.data.ModelInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    vm: PocketViewModel,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onModelLoaded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allModels by vm.models.collectAsStateWithLifecycle()
    val searchModels by vm.embeddingModels.collectAsStateWithLifecycle()
    // Embedding models are for searching documents, not chatting, so they are listed on their own.
    val models = remember(allModels, searchModels) { allModels.filter { m -> searchModels.none { it.path == m.path } } }
    val loaded by vm.loadedModel.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val infos by vm.modelInfo.collectAsStateWithLifecycle()
    val projectors by vm.projectors.collectAsStateWithLifecycle()
    val projectorChoice by vm.projectorChoice.collectAsStateWithLifecycle()
    val gen by vm.generationParams.collectAsStateWithLifecycle()
    val modelSettings by vm.modelSettings.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val defaultPresetId by vm.selectedPresetId.collectAsStateWithLifecycle()
    var settingsFor by remember { mutableStateOf<LocalModel?>(null) }
    val gpuDevices by vm.gpuDevices.collectAsStateWithLifecycle()
    val gpuDisabled by vm.gpuDisabled.collectAsStateWithLifecycle()
    val probingGpus by vm.probingGpus.collectAsStateWithLifecycle()
    val gpuStatus by vm.gpuStatus.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val onlineCount by vm.onlineModelCount.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshCatalog() }
    val context = LocalContext.current
    val memory = rememberMemoryInfo(models)
    var url by rememberSaveable { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::import)
    }

    settingsFor?.let { model ->
        ModelSettingsDialog(
            model = model,
            current = modelSettings[model.fileName],
            appParams = gen,
            presets = presets,
            defaultPresetId = defaultPresetId,
            otherModels = models.filter { it.path != model.path },
            maxThreads = Runtime.getRuntime().availableProcessors(),
            importAdapter = { uri, done -> vm.importAdapter(uri, model, done) },
            gpuDevices = gpuDevices,
            gpuDisabled = gpuDisabled,
            checkingGpus = probingGpus,
            onCheckGpus = vm::checkForGpus,
            onAllowGpu = vm::allowGpuAgain,
            onSave = { vm.setModelSettings(model, it); settingsFor = null },
            onDismiss = { settingsFor = null },
        )
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
            if (searchModels.isNotEmpty()) {
                item { SectionTitle("For searching documents") }
                items(searchModels, key = { "search-" + it.path }) { m ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.fileName.removeSuffix(".gguf"), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    Formatter.formatShortFileSize(context, m.sizeBytes) +
                                        " · choose it in Settings, Document search",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = { vm.delete(m) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${m.fileName}") }
                        }
                    }
                }
            }
            items(models, key = { it.path }) { m ->
                val own = modelSettings[m.fileName]
                // A draft model sits in memory beside this one, so it counts towards what is needed.
                val draftBytes = own?.draftFile?.let { name -> models.firstOrNull { it.fileName == name }?.sizeBytes } ?: 0L
                val fit = MemoryEstimate.estimate(
                    m.sizeBytes + draftBytes, infos[m.path], own?.params?.contextSize ?: gen.contextSize,
                    memory.availMem, memory.totalMem, own?.params?.cacheBits ?: gen.cacheBits,
                )
                LocalModelCard(
                    model = m,
                    info = infos[m.path],
                    sizeText = Formatter.formatShortFileSize(context, m.sizeBytes),
                    fit = fit,
                    memoryText = "${Formatter.formatShortFileSize(context, memory.availMem)} free of " +
                        Formatter.formatShortFileSize(context, memory.totalMem),
                    needText = Formatter.formatShortFileSize(context, fit.neededBytes),
                    isLoaded = loaded?.path == m.path,
                    busy = loading,
                    onLoad = { vm.load(m); onModelLoaded() },
                    onUnload = vm::unload,
                    onDelete = { vm.delete(m) },
                    projectors = projectors,
                    projector = projectorChoice[m.fileName],
                    onProjector = { vm.setProjector(m, it) },
                    verified = m.verified,
                    hasOwnSettings = own != null,
                    runningOn = if (loaded?.path == m.path && gpuStatus.text.isNotEmpty()) {
                        (if (gpuStatus.onGpu) "Running on the GPU: " else "GPU not used: ") + gpuStatus.text
                    } else null,
                    onSettings = { settingsFor = m },
                )
            }
            item {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null)
                    Text("  Import .gguf from storage")
                }
            }

            item { SectionTitle("Download") }
            val levelOf = { c: CatalogModel ->
                MemoryEstimate.estimate(c.sizeMb * 1024L * 1024L, null, gen.contextSize, memory.availMem, memory.totalMem, gen.cacheBits).level
            }
            val recommended = Recommendation.pick(catalog, levelOf)
            if (onlineCount > 0) {
                item {
                    Text(
                        "$onlineCount more ${if (onlineCount == 1) "model" else "models"} from PocketLM's online list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(catalog, key = { it.fileName }) { c ->
                CatalogCard(
                    level = levelOf(c),
                    recommended = c == recommended,
                    model = c,
                    installed = models.any { it.fileName == c.fileName } &&
                        (c.projectorFileName == null || projectors.any { it.fileName == c.projectorFileName }),
                    progress = downloads[c.fileName] ?: c.projectorFileName?.let { downloads[it] },
                    onDownload = { vm.download(c) },
                    onCancel = { vm.cancelDownload(c.fileName) },
                )
            }

            item {
                OutlinedButton(onClick = onOpenSearch, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Text("  Find models online")
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
    info: ModelInfo?,
    sizeText: String,
    fit: MemoryEstimate.Result,
    memoryText: String,
    needText: String,
    isLoaded: Boolean,
    busy: Boolean,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
    projectors: List<LocalModel>,
    projector: String?,
    onProjector: (String?) -> Unit,
    verified: Boolean,
    hasOwnSettings: Boolean,
    runningOn: String?,
    onSettings: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(model.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(sizeText, info?.summary?.takeIf { it.isNotEmpty() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            val fitColor = when (fit.level) {
                MemoryEstimate.Level.Comfortable -> MaterialTheme.colorScheme.primary
                MemoryEstimate.Level.Tight -> MaterialTheme.colorScheme.tertiary
                MemoryEstimate.Level.TooBig -> MaterialTheme.colorScheme.error
            }
            Text(
                when (fit.level) {
                    MemoryEstimate.Level.Comfortable -> "Should fit: about $needText needed ($memoryText)"
                    MemoryEstimate.Level.Tight -> "Tight fit: about $needText needed ($memoryText)"
                    MemoryEstimate.Level.TooBig -> "Probably too big: about $needText needed ($memoryText)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = fitColor,
            )
            if (verified) {
                Text("Checksum verified: this is the file the model site published", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            runningOn?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            var confirmLoad by remember { mutableStateOf(false) }
            if (confirmLoad) {
                AlertDialog(
                    onDismissRequest = { confirmLoad = false },
                    title = { Text("This model may not fit") },
                    text = {
                        Text(
                            "It needs about $needText of memory and your phone has $memoryText. " +
                                "Loading it could be very slow or close the app. You can lower the context length in Settings to use less."
                        )
                    },
                    confirmButton = { TextButton(onClick = { confirmLoad = false; onLoad() }) { Text("Load anyway") } },
                    dismissButton = { TextButton(onClick = { confirmLoad = false }) { Text("Cancel") } },
                )
            }
            var showDetails by rememberSaveable { mutableStateOf(false) }
            if (showDetails && info != null) {
                Column(Modifier.padding(top = 8.dp)) {
                    DetailRow("Name", info.name)
                    DetailRow("Architecture", info.architecture)
                    DetailRow("Parameters", info.parameters)
                    DetailRow("Quantisation", info.quantisation)
                    DetailRow("Trained context", info.contextLength?.let { "%,d tokens".format(it) })
                    DetailRow("Layers", info.layers?.toString())
                    DetailRow("Embedding size", info.embeddingSize?.toString())
                    DetailRow("License", info.license)
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isLoaded) {
                    Button(onClick = onUnload) { Text("Unload") }
                } else {
                    Button(
                        onClick = { if (fit.level == MemoryEstimate.Level.TooBig) confirmLoad = true else onLoad() },
                        enabled = !busy,
                    ) { Text("Load") }
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text(" Delete")
                }
                if (info != null) {
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "Hide details" else "Details")
                    }
                }
            }
            TextButton(onClick = onSettings) {
                Icon(Icons.Filled.Tune, contentDescription = null)
                Text(if (hasOwnSettings) "  Settings for this model (custom)" else "  Settings for this model")
            }
            if (projectors.isNotEmpty()) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.Image, contentDescription = null)
                        Text("  Vision: ${projector?.removeSuffix(".gguf") ?: "off"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Off") }, onClick = { onProjector(null); menuOpen = false })
                        projectors.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.fileName) },
                                onClick = { onProjector(p.fileName); menuOpen = false },
                            )
                        }
                    }
                }
                Text(
                    "Pick the add-on file made for this model. Applies the next time it loads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CatalogCard(
    level: MemoryEstimate.Level,
    recommended: Boolean,
    model: CatalogModel,
    installed: Boolean,
    progress: com.pocketlm.app.DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(model.name, style = MaterialTheme.typography.titleSmall)
            if (recommended) {
                Text("Recommended for this phone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text("${model.sizeMb} MB · ${model.description}", style = MaterialTheme.typography.bodySmall)
            Text(
                when (level) {
                    MemoryEstimate.Level.Comfortable -> "Should fit comfortably on this phone"
                    MemoryEstimate.Level.Tight -> "Tight fit on this phone"
                    MemoryEstimate.Level.TooBig -> "Probably too big for this phone"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (level) {
                    MemoryEstimate.Level.Comfortable -> MaterialTheme.colorScheme.onSurfaceVariant
                    MemoryEstimate.Level.Tight -> MaterialTheme.colorScheme.tertiary
                    MemoryEstimate.Level.TooBig -> MaterialTheme.colorScheme.error
                },
            )
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
