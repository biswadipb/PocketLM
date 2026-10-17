package com.pocketlm.app.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import android.text.format.Formatter
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import com.pocketlm.app.data.AdapterSetting
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arm.aichat.GenerationParams
import com.arm.aichat.GpuDevice
import com.pocketlm.app.data.ChatTemplates
import com.pocketlm.app.data.GpuChoice
import com.pocketlm.app.data.summary
import com.pocketlm.app.data.LocalModel
import com.pocketlm.app.data.ModelSettings
import com.pocketlm.app.data.Preset

/**
 * Settings for one model: how it writes, its own system prompt, a chat template for models whose own is
 * missing or wrong, and a smaller model that drafts ahead to speed it up. Anything left on its default follows
 * the app-wide settings.
 */
@Composable
fun ModelSettingsDialog(
    model: LocalModel,
    current: ModelSettings?,
    appParams: GenerationParams,
    presets: List<Preset>,
    defaultPresetId: String,
    otherModels: List<LocalModel>,
    maxThreads: Int,
    importAdapter: (Uri, (name: String?, problem: String?) -> Unit) -> Unit,
    gpuDevices: List<GpuDevice>?,
    gpuDisabled: Boolean,
    checkingGpus: Boolean,
    onCheckGpus: () -> Unit,
    onAllowGpu: () -> Unit,
    onSave: (ModelSettings?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var ownStyle by remember { mutableStateOf(current?.params != null) }
    var params by remember { mutableStateOf(current?.params ?: appParams) }
    var presetId by remember { mutableStateOf(current?.presetId) }
    var template by remember { mutableStateOf(current?.chatTemplate.orEmpty()) }
    var draft by remember { mutableStateOf(current?.draftFile) }
    var gpu by remember { mutableStateOf(GpuChoice.fromId(current?.gpu)) }
    var adapters by remember { mutableStateOf(current?.adapters.orEmpty()) }
    var adapterProblem by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val adapterPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            importAdapter(uri) { name, problem ->
                importing = false
                if (name != null) {
                    adapters = adapters.filterNot { it.file == name } + AdapterSetting(name, 1f)
                    adapterProblem = null
                } else {
                    adapterProblem = problem
                }
            }
        }
    }
    // A draft model has to be smaller than the model it helps.
    val drafts = remember(otherModels, model) { otherModels.filter { it.sizeBytes < model.sizeBytes }.sortedBy { it.sizeBytes } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.fileName.removeSuffix(".gguf"), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "These apply only while this model is loaded. Anything you leave alone follows the app-wide settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Heading("Writing style")
                SwitchRow(title = "Use settings for this model only", description = null, checked = ownStyle, onCheckedChange = { ownStyle = it })
                if (ownStyle) {
                    Column(Modifier.padding(top = 8.dp)) { GenerationControls(params, maxThreads) { params = it } }
                }

                Heading("System prompt")
                val defaultName = presets.firstOrNull { it.id == defaultPresetId }?.name ?: "Default"
                ChoiceMenu(
                    valueText = presets.firstOrNull { it.id == presetId }?.name ?: "App default ($defaultName)",
                    options = listOf<Pair<String?, String>>(null to "App default ($defaultName)") + presets.map { it.id to it.name },
                    onPick = { presetId = it },
                )

                Heading("Chat template")
                Text(
                    "How messages are wrapped for the model. Leave it on automatic unless this model gives garbled or " +
                        "runaway replies, which usually means its own template is missing or wrong.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChoiceMenu(
                    valueText = ChatTemplates.label(template),
                    options = ChatTemplates.choices.map { it.id to it.label },
                    onPick = { template = it.orEmpty() },
                )

                Heading("Adapters (LoRA)")
                Text(
                    "Small fine-tune files made for this exact model, for a style, a language or a task. They are applied on top of the " +
                        "model when it loads, so a change here needs the model loaded again. A strength of 1 is as trained; lower " +
                        "blends it in more gently.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                adapters.forEach { adapter ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            adapter.file.removeSuffix(".gguf"),
                            Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("strength " + "%.2f".format(adapter.scale), style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { adapters = adapters.filterNot { it.file == adapter.file } }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ${adapter.file}")
                        }
                    }
                    Slider(
                        value = adapter.scale,
                        onValueChange = { v -> adapters = adapters.map { if (it.file == adapter.file) it.copy(scale = (v * 20).roundToInt() / 20f) else it } },
                        valueRange = AdapterSetting.MIN_SCALE..AdapterSetting.MAX_SCALE,
                    )
                }
                OutlinedButton(onClick = { adapterPicker.launch(arrayOf("*/*")) }, enabled = !importing, modifier = Modifier.padding(top = 4.dp)) {
                    Text(if (importing) "Checking the file…" else "Add an adapter file")
                }
                adapterProblem?.let {
                    Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                Heading("GPU acceleration (experimental)")
                Text(
                    "Runs this model on the phone's graphics processor instead of the CPU. It can speed up reading long prompts " +
                        "and documents, but writing replies is often no faster, and phone GPU drivers vary a lot, so try it and " +
                        "compare with the benchmark. If the GPU does not work, PocketLM uses the CPU. Takes effect the next time " +
                        "the model loads. The draft model, document search and image reading stay on the CPU.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (gpuDisabled) {
                    Text(
                        "The GPU was switched off because PocketLM closed while trying it.",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onAllowGpu) { Text("Allow the GPU again") }
                } else {
                    ChoiceMenu(
                        valueText = gpu.label,
                        options = GpuChoice.entries.map { it.id to it.label },
                        onPick = { gpu = GpuChoice.fromId(it) },
                    )
                    TextButton(onClick = onCheckGpus, enabled = !checkingGpus) {
                        Text(if (checkingGpus) "Checking…" else "Check this phone for GPUs")
                    }
                    gpuDevices?.let { devices ->
                        Text(
                            if (devices.isEmpty()) "No GPU that PocketLM can use was found."
                            else "Found:\n" + devices.joinToString("\n") { it.summary() },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Heading("Draft model (experimental)")
                Text(
                    "A smaller model of the same family guesses ahead and this one checks the guesses, which can make replies " +
                        "faster without changing them. It stays in memory beside this model, so it needs a phone with room " +
                        "to spare. It must use the same vocabulary, and it is switched off while replies are restricted to JSON.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (drafts.isEmpty()) {
                    Text(
                        "Download a smaller model to use as a draft.",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    ChoiceMenu(
                        valueText = drafts.firstOrNull { it.fileName == draft }?.fileName?.removeSuffix(".gguf") ?: "Off",
                        options = listOf<Pair<String?, String>>(null to "Off") + drafts.map {
                            it.fileName to (it.fileName.removeSuffix(".gguf") + " (" + Formatter.formatShortFileSize(context, it.sizeBytes) + ")")
                        },
                        onPick = { draft = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = ModelSettings(
                    params = params.takeIf { ownStyle },
                    presetId = presetId,
                    chatTemplate = template,
                    draftFile = draft,
                    gpu = gpu.id,
                    adapters = adapters,
                )
                onSave(
                    result.takeIf {
                        it.params != null || it.presetId != null || it.chatTemplate.isNotEmpty() || it.draftFile != null || it.gpu.isNotEmpty() || it.adapters.isNotEmpty()
                    },
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A button showing the current choice that opens a menu of the others. */
@Composable
private fun ChoiceMenu(valueText: String, options: List<Pair<String?, String>>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) {
            Text(valueText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onPick(id); open = false })
            }
        }
    }
}
