package com.pocketlm.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketlm.app.data.LocalModel
import com.pocketlm.app.data.Preset

/** Settings that belong to one chat: its model, its system prompt, and JSON-only replies. */
@Composable
fun ChatOptionsDialog(
    models: List<LocalModel>,
    loaded: LocalModel?,
    presets: List<Preset>,
    chatPresetId: String?,
    defaultPresetId: String,
    jsonMode: Boolean,
    onModel: (LocalModel) -> Unit,
    onPreset: (String?) -> Unit,
    onJsonMode: (Boolean) -> Unit,
    onShareChat: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat options") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Heading("Model for this chat")
                if (models.isEmpty()) {
                    Text("No downloaded models yet.", style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.selectableGroup()) {
                    models.forEach { m ->
                        Choice(m.fileName.removeSuffix(".gguf"), selected = m.path == loaded?.path) { onModel(m) }
                    }
                }

                Heading("System prompt for this chat")
                Column(Modifier.selectableGroup()) {
                    val defaultName = presets.firstOrNull { it.id == defaultPresetId }?.name ?: "Default"
                    Choice("Use the default ($defaultName)", selected = chatPresetId == null) { onPreset(null) }
                    presets.forEach { p ->
                        Choice(p.name, selected = chatPresetId == p.id) { onPreset(p.id) }
                    }
                }

                Heading("JSON output")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Only allow valid JSON in replies. Ask for what you want in your message, for example " +
                            "\"List three fruits as a JSON object\".",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(checked = jsonMode, onCheckedChange = onJsonMode)
                }
                if (onShareChat != null) {
                    Heading("Share")
                    TextButton(onClick = onShareChat) { Text("Share this chat as text") }
                }
                Text(
                    "Changing the model or prompt reloads the model and replays this chat on your next message.",
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        Modifier.padding(top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
