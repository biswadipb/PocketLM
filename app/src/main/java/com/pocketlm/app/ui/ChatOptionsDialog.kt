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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.OutlinedTextField
import com.pocketlm.app.data.JsonSchema
import com.pocketlm.app.data.Preset
import com.pocketlm.app.data.Project

/** Settings that belong to one chat: its model, its system prompt, and JSON-only replies. */
@Composable
fun ChatOptionsDialog(
    models: List<LocalModel>,
    loaded: LocalModel?,
    presets: List<Preset>,
    chatPresetId: String?,
    defaultPresetId: String,
    jsonMode: Boolean,
    jsonSchema: String?,
    thinkingSupported: Boolean,
    thinking: Boolean,
    onThinking: (Boolean) -> Unit,
    notesFolder: String?,
    useNotes: Boolean,
    onUseNotes: (Boolean) -> Unit,
    projects: List<Project>,
    projectId: String?,
    onProject: (String?) -> Unit,
    onModel: (LocalModel) -> Unit,
    onPreset: (String?) -> Unit,
    onJsonMode: (Boolean) -> Unit,
    onJsonSchema: (String?) -> Unit,
    canSummarise: Boolean,
    onSummarise: () -> Unit,
    onSuggestNotes: () -> Unit,
    onShowPrompt: (() -> Unit)?,
    onShareChat: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var editingSchema by remember { mutableStateOf(false) }
    if (editingSchema) {
        SchemaDialog(
            initial = jsonSchema,
            onSave = { onJsonSchema(it); editingSchema = false },
            onDismiss = { editingSchema = false },
        )
    }
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

                if (projects.isNotEmpty()) {
                    Heading("Project")
                    Column(Modifier.selectableGroup()) {
                        Choice("None", selected = projectId == null) { onProject(null) }
                        projects.forEach { p -> Choice(p.name, selected = projectId == p.id) { onProject(p.id) } }
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

                if (thinkingSupported) {
                    Heading("Thinking")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "This model can think before it answers. Turn it off to get answers straight away, which is much " +
                                "faster on a phone but can be less careful with maths and puzzles. Applies to your next message.",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Switch(checked = thinking, onCheckedChange = onThinking)
                    }
                }

                if (notesFolder != null) {
                    Heading("My notes")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Look for the answer in your notes folder ($notesFolder) too, and show the model the passages it finds. " +
                                "They stay on this phone.",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Switch(checked = useNotes, onCheckedChange = onUseNotes)
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
                if (jsonMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (jsonSchema != null) "Replies must follow your schema." else "Replies can be any valid JSON.",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { editingSchema = true }) {
                            Text(if (jsonSchema != null) "Edit schema" else "Use a schema")
                        }
                        if (jsonSchema != null) TextButton(onClick = { onJsonSchema(null) }) { Text("Remove") }
                    }
                }

                Heading("This conversation")
                TextButton(onClick = onSummarise, enabled = canSummarise) { Text("Summarise and continue") }
                Text(
                    "The model condenses the chat so far into a short summary and carries on from it, freeing up memory. " +
                        "Your messages stay on screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onSuggestNotes, enabled = canSummarise) { Text("Suggest memory notes") }
                Text(
                    "The model reads what you said and offers facts about you worth remembering in every chat. You choose which to keep.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onShowPrompt != null) {
                    TextButton(onClick = onShowPrompt) { Text("Show what the model received") }
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

/** Typing a JSON Schema the replies must follow. */
@Composable
private fun SchemaDialog(initial: String?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial ?: JsonSchema.EXAMPLE) }
    val problem = JsonSchema.problem(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("JSON schema") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Schema") },
                    isError = problem != null,
                    supportingText = { Text(problem ?: "Replies will have exactly this shape.") },
                    minLines = 6,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = problem == null) { Text("Use schema") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
