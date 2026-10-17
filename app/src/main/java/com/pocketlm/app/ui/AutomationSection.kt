package com.pocketlm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.NotesUse
import com.pocketlm.app.data.ScheduledPrompt
import com.pocketlm.app.data.Schedules
import java.time.DayOfWeek
import java.util.UUID

/** Settings for letting other apps (Tasker and the like) send PocketLM a prompt. */
@Composable
internal fun AutomationSection(vm: PocketViewModel) {
    val enabled by vm.automationEnabled.collectAsStateWithLifecycle()
    val token by vm.automationToken.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    SectionTitle("Automation", Modifier.padding(top = 24.dp))
    Text(
        "Let Tasker, MacroDroid, Automate or a similar app send PocketLM a prompt and get the answer back, even when PocketLM is " +
            "closed. It is off by default. A sender has to present the secret below, the answer goes only to the app it names, and your " +
            "memory notes are never included. The model is loaded for the request and unloaded again.",
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
    SwitchRow(
        title = "Let other apps ask PocketLM",
        description = null,
        checked = enabled,
        onCheckedChange = vm::setAutomationEnabled,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (!enabled || token == null) return

    Text("Secret", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, color = muted)
    SelectionContainer { Text(token.orEmpty(), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { clipboard.setText(AnnotatedString(token.orEmpty())) }) { Text("Copy secret") }
        TextButton(onClick = vm::regenerateAutomationToken) { Text("Make a new secret") }
    }
    Text(
        "In Tasker, add a Send Intent action:\n" +
            "Action: com.pocketlm.app.action.ASK\n" +
            "Package: com.pocketlm.app\n" +
            "Target: Broadcast Receiver\n" +
            "Extras (each as name:value): token, prompt\n" +
            "Optional extras: system, model, max_tokens (default 512), reply_package, reply_action, request_id, notify, save_chat\n\n" +
            "To get the answer, set reply_package to the app that should receive it (Tasker is net.dinglisch.android.taskerm). " +
            "PocketLM then sends it a broadcast with the action in reply_action (default com.pocketlm.app.action.REPLY) and the " +
            "extras reply (the answer), error (if it failed), model and request_id. In Tasker, catch it with an Intent Received event.",
        Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = muted,
    )
}

/** Settings for prompts PocketLM runs by itself at set times. */
@Composable
internal fun ScheduledPromptsSection(vm: PocketViewModel) {
    val schedules by vm.scheduledPrompts.collectAsStateWithLifecycle()
    val notesStatus by vm.notesStatus.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var editing by remember { mutableStateOf<ScheduledPrompt?>(null) }
    var deleting by remember { mutableStateOf<ScheduledPrompt?>(null) }

    SectionTitle("Scheduled prompts", Modifier.padding(top = 24.dp))
    Text(
        "Have PocketLM run a prompt by itself at a set time, such as a summary of the notes that changed overnight. The answer " +
            "arrives as a notification and as a chat in History. Android picks the exact moment, usually within about 20 minutes after " +
            "the time you choose, and only when the battery is not low. The model is loaded for the run and unloaded again, so it " +
            "uses battery and memory while it works.",
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
    schedules.forEach { s ->
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            SwitchRow(
                title = s.title,
                description = "${Schedules.timeLabel(s.hour, s.minute)} · ${Schedules.dayLabel(s.days)}" +
                    when (s.notes) {
                        NotesUse.None -> ""
                        NotesUse.Recent -> " · notes that changed"
                        NotesUse.Search -> " · searches my notes"
                    } + if (s.lastNote.isNotEmpty()) "\nLast run: ${s.lastNote}" else "",
                checked = s.enabled,
                onCheckedChange = { vm.setScheduleEnabled(s.id, it) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { editing = s }) { Text("Edit") }
                TextButton(onClick = { vm.runScheduleNow(s.id) }) { Text("Run now") }
                TextButton(onClick = { deleting = s }) { Text("Delete") }
            }
        }
    }
    OutlinedButton(onClick = { editing = newSchedule() }, Modifier.padding(top = 8.dp)) { Text("Add a scheduled prompt") }

    editing?.let { current ->
        ScheduleDialog(
            initial = current,
            notesChosen = notesStatus.folderName != null,
            models = models.map { it.fileName },
            onSave = { schedule -> vm.saveSchedule(schedule).also { if (it == null) editing = null } },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${s.title}\"?") },
            text = { Text("It will not run any more. Chats it already made stay in History.") },
            confirmButton = { TextButton(onClick = { vm.deleteSchedule(s.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun newSchedule() = ScheduledPrompt(
    id = UUID.randomUUID().toString(),
    title = "",
    prompt = "",
    hour = 8,
    minute = 0,
)

@Composable
private fun ScheduleDialog(
    initial: ScheduledPrompt,
    notesChosen: Boolean,
    models: List<String>,
    onSave: (ScheduledPrompt) -> String?,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var prompt by remember { mutableStateOf(initial.prompt) }
    var hour by remember { mutableStateOf(initial.hour.toString()) }
    var minute by remember { mutableStateOf("%02d".format(initial.minute)) }
    var days by remember { mutableStateOf(initial.days) }
    var notes by remember { mutableStateOf(initial.notes) }
    var model by remember { mutableStateOf(initial.model) }
    var menu by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.title.isEmpty()) "New scheduled prompt" else "Edit scheduled prompt") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hour,
                        onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                        label = { Text("Hour (0–23)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = minute,
                        onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                        label = { Text("Minute") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("Days", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        val on = Schedules.runsOn(days, day)
                        FilterChip(
                            selected = on,
                            onClick = { days = days xor Schedules.dayBit(day) },
                            label = { Text(day.name.take(1) + day.name.substring(1, 2).lowercase()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text("Notes", style = MaterialTheme.typography.labelMedium)
                Column(Modifier.selectableGroup()) {
                    NotesChoice("Only the prompt", notes == NotesUse.None, true) { notes = NotesUse.None }
                    NotesChoice("The notes that changed since the last run", notes == NotesUse.Recent, notesChosen) { notes = NotesUse.Recent }
                    NotesChoice("Search my notes for the prompt", notes == NotesUse.Search, notesChosen) { notes = NotesUse.Search }
                }
                if (!notesChosen) {
                    Text(
                        "Choose a notes folder in Settings, My notes, to use your notes here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Model", style = MaterialTheme.typography.labelMedium)
                Column {
                    OutlinedButton(onClick = { menu = true }) { Text(model?.removeSuffix(".gguf") ?: "The one used last") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("The one used last") }, onClick = { model = null; menu = false })
                        models.forEach { file ->
                            DropdownMenuItem(text = { Text(file.removeSuffix(".gguf")) }, onClick = { model = file; menu = false })
                        }
                    }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                problem = onSave(
                    initial.copy(
                        title = title.trim(), prompt = prompt.trim(),
                        hour = hour.toIntOrNull() ?: -1, minute = minute.toIntOrNull() ?: -1,
                        days = days, notes = notes, model = model,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NotesChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 12.dp))
    }
}
