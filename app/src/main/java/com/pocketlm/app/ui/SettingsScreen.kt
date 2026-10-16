package com.pocketlm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.pocketlm.app.ApiStatus
import com.pocketlm.app.data.IdlePolicy
import com.pocketlm.app.data.LocalNetwork
import com.pocketlm.app.data.ToolId
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arm.aichat.GenerationParams
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.Preset
import java.util.UUID
import kotlin.math.roundToInt

private val CONTEXT_SIZES = listOf(1024, 2048, 4096, 8192, 16384)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: PocketViewModel,
    onBack: () -> Unit,
    onOpenBenchmark: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenCompare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    val params by vm.generationParams.collectAsStateWithLifecycle()
    val stopWords by vm.stopWords.collectAsStateWithLifecycle()
    val lockEnabled by vm.lockEnabled.collectAsStateWithLifecycle()
    val readAloud by vm.readAloud.collectAsStateWithLifecycle()
    val backgroundReplies by vm.backgroundReplies.collectAsStateWithLifecycle()
    val enabledTools by vm.enabledTools.collectAsStateWithLifecycle()
    val idleMinutes by vm.idleMinutes.collectAsStateWithLifecycle()
    val maxReply by vm.maxReplyTokens.collectAsStateWithLifecycle()
    val searchServer by vm.searchServer.collectAsStateWithLifecycle()
    val apiEnabled by vm.apiEnabled.collectAsStateWithLifecycle()
    val apiStatus by vm.apiStatus.collectAsStateWithLifecycle()
    val apiPort by vm.apiPort.collectAsStateWithLifecycle()
    val apiKey by vm.apiKey.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val presets by vm.presets.collectAsStateWithLifecycle()
    val selectedId by vm.selectedPresetId.collectAsStateWithLifecycle()
    val maxThreads = Runtime.getRuntime().availableProcessors()
    var editing by remember { mutableStateOf<Preset?>(null) }

    editing?.let { preset ->
        PresetDialog(
            initial = preset,
            onSave = { vm.savePreset(it); vm.selectPreset(it.id); editing = null },
            onDismiss = { editing = null },
        )
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionTitle("Appearance")
            Column(Modifier.selectableGroup().padding(top = 8.dp)) {
                ThemeMode.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == mode,
                                onClick = { vm.setThemeMode(option) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == mode, onClick = null)
                        Text(option.label, Modifier.padding(start = 12.dp))
                    }
                }
            }

            SectionTitle("Local API server", Modifier.padding(top = 24.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Let other apps use this model")
                    Text(
                        "Starts an OpenAI-compatible server on this phone, so tools on your Wi-Fi can chat with the " +
                            "loaded model. Keep PocketLM open with a model loaded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = apiEnabled, onCheckedChange = vm::setApiEnabled)
            }
            if (apiEnabled) {
                when (val status = apiStatus) {
                    is ApiStatus.Running -> {
                        val addresses = remember(status) { LocalNetwork.addresses() }
                        Text(
                            if (addresses.isEmpty()) "Running on port ${status.port}, but no Wi-Fi address was found."
                            else "Running. Base URL:\n" + addresses.joinToString("\n") { "http://$it:${status.port}/v1" },
                            Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is ApiStatus.Failed -> Text(
                        status.message, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ApiStatus.Stopped -> Text("Starting…", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Text("API key", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
                Text(apiKey, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(apiKey)) }) { Text("Copy key") }
                    TextButton(onClick = vm::regenerateApiKey) { Text("New key") }
                }
                var portText by remember(apiPort) { mutableStateOf(apiPort.toString()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { portText.toIntOrNull()?.let(vm::setApiPort) },
                        enabled = portText != apiPort.toString() && portText.isNotEmpty(),
                    ) { Text("Apply") }
                }
                Text(
                    "Send the key as an Authorization: Bearer header. Anyone on your network who has the key can use " +
                        "your model, and the connection is not encrypted, so only use it on a network you trust.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle("Backup", Modifier.padding(top = 24.dp))
            Text(
                "Save your chats (with their photos and attached files), saved prompts, presets and settings in one file, " +
                    "and load them on this or another phone. Downloaded models are not included, and neither are the API key or app lock.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val backupSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                uri?.let(vm::exportBackup)
            }
            val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let(vm::importBackup)
            }
            Row(Modifier.padding(top = 4.dp)) {
                TextButton(onClick = { backupSaver.launch("pocketlm-backup-${java.time.LocalDate.now()}.zip") }) { Text("Back up") }
                TextButton(onClick = { backupPicker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore") }
            }

            SectionTitle("Memory", Modifier.padding(top = 24.dp))
            Text(
                "Unload the model after you stop using it, so its memory goes back to your phone. " +
                    "Your chat stays open and the model loads again when you send a message.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.selectableGroup().padding(top = 4.dp)) {
                IdlePolicy.choices.forEach { minutes ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = minutes == idleMinutes, onClick = { vm.setIdleMinutes(minutes) }, role = Role.RadioButton)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = minutes == idleMinutes, onClick = null)
                        Text(IdlePolicy.label(minutes), Modifier.padding(start = 12.dp))
                    }
                }
            }

            SectionTitle("Model tools", Modifier.padding(top = 24.dp))
            Text(
                "Let the model use helpers while it answers. Applies the next time a model loads or you start a new chat. " +
                    "Small models do not always use tools well, so try it and see.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToolId.entries.forEach { tool ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tool.label)
                        Text(tool.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = tool in enabledTools, onCheckedChange = { vm.setToolEnabled(tool, it) })
                }
            }

            if (ToolId.WebSearch in enabledTools) {
                OutlinedTextField(
                    value = searchServer,
                    onValueChange = vm::setSearchServer,
                    label = { Text("Search server address") },
                    placeholder = { Text("search.example.org") },
                    supportingText = { Text("A SearXNG server that allows JSON results, ideally your own. The model's search words are sent to it.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            SectionTitle("Notifications", Modifier.padding(top = 24.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep replies going in the background")
                    Text(
                        "A long reply carries on when you switch apps or the screen turns off, and you get a " +
                            "notification when it is ready. A small notification shows while one is being written.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = backgroundReplies, onCheckedChange = vm::setBackgroundReplies)
            }

            SectionTitle("Voice", Modifier.padding(top = 24.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Read replies aloud")
                    Text(
                        "Speak each reply when it finishes, using your phone's voice. " +
                            "You can also tap the speaker on any reply. Code blocks are skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = readAloud, onCheckedChange = vm::setReadAloud)
            }

            SectionTitle("Privacy", Modifier.padding(top = 24.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Require unlock")
                    Text(
                        "Ask for your fingerprint, face or screen lock when opening the app. " +
                            "Also hides it in recent apps and blocks screenshots.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = lockEnabled, onCheckedChange = vm::setAppLock)
            }

            SectionTitle("Tools", Modifier.padding(top = 24.dp))
            TextButton(onClick = onOpenBenchmark, Modifier.fillMaxWidth()) {
                Text("Benchmark the loaded model", Modifier.weight(1f))
            }
            TextButton(onClick = onOpenCompare, Modifier.fillMaxWidth()) {
                Text("Compare two models", Modifier.weight(1f))
            }
            TextButton(onClick = onOpenStorage, Modifier.fillMaxWidth()) {
                Text("Storage and cleanup", Modifier.weight(1f))
            }

            Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("System prompt", Modifier.weight(1f))
                TextButton(onClick = { editing = Preset(UUID.randomUUID().toString(), "", "") }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(" New")
                }
            }
            Text(
                "Tells the model how to behave. Applies the next time a model loads or you start a new chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.selectableGroup().padding(top = 4.dp)) {
                presets.forEach { preset ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = preset.id == selectedId,
                                onClick = { vm.selectPreset(preset.id) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = preset.id == selectedId, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(preset.name)
                            Text(
                                preset.prompt,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!preset.builtIn) {
                            IconButton(onClick = { editing = preset }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit ${preset.name}")
                            }
                            IconButton(onClick = { vm.deletePreset(preset) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${preset.name}")
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Generation", Modifier.weight(1f))
                TextButton(onClick = { vm.setGenerationParams(GenerationParams()) }) { Text("Reset") }
            }
            Text(
                "Applies the next time a model loads or you start a new chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                SettingSlider(
                    label = "Temperature",
                    value = params.temperature,
                    valueText = "%.2f".format(params.temperature),
                    hint = "Lower is more focused, higher is more creative.",
                    range = 0f..2f,
                    steps = 39,
                    onChange = { vm.setGenerationParams(params.copy(temperature = (it * 20).roundToInt() / 20f)) },
                )
                SettingSlider(
                    label = "Top-p",
                    value = params.topP,
                    valueText = "%.2f".format(params.topP),
                    hint = "Only sample from the most likely words that add up to this share.",
                    range = 0.1f..1f,
                    steps = 17,
                    onChange = { vm.setGenerationParams(params.copy(topP = (it * 20).roundToInt() / 20f)) },
                )
                val ctxIndex = CONTEXT_SIZES.indexOf(params.contextSize).coerceAtLeast(0)
                SettingSlider(
                    label = "Context length",
                    value = ctxIndex.toFloat(),
                    valueText = "${params.contextSize} tokens",
                    hint = "How much of the conversation the model can remember. Bigger uses more memory.",
                    range = 0f..(CONTEXT_SIZES.lastIndex).toFloat(),
                    steps = CONTEXT_SIZES.size - 2,
                    onChange = { vm.setGenerationParams(params.copy(contextSize = CONTEXT_SIZES[it.roundToInt()])) },
                )
                SettingSlider(
                    label = "Max reply length",
                    value = maxReply.toFloat(),
                    valueText = "$maxReply tokens",
                    hint = "A reply stops at this length and shows a Continue button. Applies to the next reply.",
                    range = 128f..4096f,
                    steps = 30,
                    onChange = { vm.setMaxReplyTokens(((it / 128).roundToInt() * 128).coerceIn(128, 4096)) },
                )
                SettingSlider(
                    label = "Top-k",
                    value = params.topK.toFloat(),
                    valueText = if (params.topK == 0) "Off" else params.topK.toString(),
                    hint = "Only consider the k most likely words at each step. 0 turns it off.",
                    range = 0f..200f,
                    steps = 0,
                    onChange = { vm.setGenerationParams(params.copy(topK = it.roundToInt())) },
                )
                SettingSlider(
                    label = "Min-p",
                    value = params.minP,
                    valueText = "%.2f".format(params.minP),
                    hint = "Drop words far less likely than the best one. 0 turns it off.",
                    range = 0f..0.5f,
                    steps = 49,
                    onChange = { vm.setGenerationParams(params.copy(minP = (it * 100).roundToInt() / 100f)) },
                )
                SettingSlider(
                    label = "Repeat penalty",
                    value = params.repeatPenalty,
                    valueText = "%.2f".format(params.repeatPenalty),
                    hint = "Higher discourages repeating itself. 1.00 turns it off.",
                    range = 1f..2f,
                    steps = 19,
                    onChange = { vm.setGenerationParams(params.copy(repeatPenalty = (it * 20).roundToInt() / 20f)) },
                )
                SettingSlider(
                    label = "CPU threads",
                    value = params.threads.toFloat(),
                    valueText = if (params.threads == 0) "Auto" else params.threads.toString(),
                    hint = "Auto picks a safe number for your phone.",
                    range = 0f..maxThreads.toFloat(),
                    steps = maxThreads - 1,
                    onChange = { vm.setGenerationParams(params.copy(threads = it.roundToInt())) },
                )
                OutlinedTextField(
                    value = if (params.seed < 0) "" else params.seed.toString(),
                    onValueChange = { text ->
                        val digits = text.filter { it.isDigit() }.take(9)
                        vm.setGenerationParams(params.copy(seed = digits.toIntOrNull() ?: -1))
                    },
                    label = { Text("Seed") },
                    placeholder = { Text("Random") },
                    supportingText = { Text("Use the same number to get repeatable replies. Leave empty for random.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = stopWords,
                    onValueChange = vm::setStopWords,
                    label = { Text("Stop words") },
                    supportingText = { Text("One per line. A reply ends as soon as one appears. Applies immediately.") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PresetDialog(initial: Preset, onSave: (Preset) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var prompt by remember { mutableStateOf(initial.prompt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isEmpty()) "New preset" else "Edit preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("System prompt") },
                    minLines = 4,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(name = name.trim(), prompt = prompt.trim())) },
                enabled = name.isNotBlank() && prompt.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    valueText: String,
    hint: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
