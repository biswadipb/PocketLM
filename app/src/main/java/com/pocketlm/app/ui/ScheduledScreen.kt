package com.pocketlm.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.NotesUse
import com.pocketlm.app.data.ScheduledPrompt
import com.pocketlm.app.data.Schedules
import com.pocketlm.app.data.TaskParser
import com.pocketlm.app.data.TaskTemplate
import com.pocketlm.app.data.TaskTemplates
import com.pocketlm.app.data.ToolId
import com.pocketlm.app.data.toSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** A task being edited, with what the app understood from the person's description. */
private class TaskEdit(val schedule: ScheduledPrompt, val hint: String? = null, val needs: Set<ToolId> = emptySet())

/**
 * Tasks Piko does by itself at set times: describe one in a sentence, start from a suggestion, and see what each one last
 * did. The same tasks are listed (in a plainer form) in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(vm: PocketViewModel, onBack: () -> Unit, onOpened: () -> Unit, modifier: Modifier = Modifier) {
    val schedules by vm.scheduledPrompts.collectAsStateWithLifecycle()
    val notesStatus by vm.notesStatus.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val enabledTools by vm.enabledTools.collectAsStateWithLifecycle()
    var described by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<TaskEdit?>(null) }
    var deleting by remember { mutableStateOf<ScheduledPrompt?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    fun startFromDescription() {
        val text = described.trim()
        if (text.isEmpty()) return
        val draft = TaskParser.parse(text)
        val hint = if (draft.whenFound) "Piko read this as: ${draft.whenLabel}. Check it, then save."
        else "The description does not say when, so it is set to ${draft.whenLabel}. Pick the time and days you want."
        editing = TaskEdit(draft.toSchedule(UUID.randomUUID().toString()), hint)
        described = ""
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Scheduled") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Let Piko handle routines, track updates and take action for you. Each task runs by itself at its time, saves " +
                        "its answer in History and sends a notification.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }
            item {
                OutlinedTextField(
                    value = described,
                    onValueChange = { described = it.take(Schedules.MAX_PROMPT_CHARS) },
                    placeholder = { Text("Describe a task") },
                    supportingText = { Text("For example: every morning at 8, give me a news digest about AI") },
                    trailingIcon = {
                        IconButton(onClick = ::startFromDescription, enabled = described.isNotBlank()) {
                            Icon(Icons.Filled.Add, contentDescription = "Create this task")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { startFromDescription() }),
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (schedules.isNotEmpty()) {
                item { Text("Your tasks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(schedules, key = { it.id }) { task ->
                    TaskCard(
                        task,
                        onToggle = { vm.setScheduleEnabled(task.id, it) },
                        onOpenResult = { task.lastChat?.let { vm.openChatById(it); onOpened() } },
                        onRunNow = { vm.runScheduleNow(task.id) },
                        onEdit = { editing = TaskEdit(task) },
                        onDelete = { deleting = task },
                    )
                }
            }

            item { Text("Suggested", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(TaskTemplates.all, key = { it.id }) { template ->
                SuggestionCard(template, missing = TaskTemplates.missing(template, enabledTools)) {
                    editing = TaskEdit(template.toSchedule(UUID.randomUUID().toString()), needs = template.needs)
                }
            }

            item {
                Text(
                    "Android picks the exact moment, usually within about 20 minutes after the time you choose, and only when the " +
                        "battery is not low. The model is loaded for the run and unloaded again, so a task uses battery and memory " +
                        "while it works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }

    editing?.let { edit ->
        ScheduleDialog(
            initial = edit.schedule,
            notesChosen = notesStatus.folderName != null,
            models = models.map { it.fileName },
            enabledTools = enabledTools,
            onEnableTool = { vm.setToolEnabled(it, true) },
            isNew = schedules.none { it.id == edit.schedule.id },
            onSave = { schedule -> vm.saveSchedule(schedule).also { if (it == null) editing = null } },
            onDismiss = { editing = null },
            hint = edit.hint,
            needs = edit.needs,
        )
    }
    deleting?.let { task ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${task.title}\"?") },
            text = { Text("It will not run any more. Chats it already made stay in History.") },
            confirmButton = { TextButton(onClick = { vm.deleteSchedule(task.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private val whenFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())

@Composable
private fun TaskCard(
    task: ScheduledPrompt,
    onToggle: (Boolean) -> Unit,
    onOpenResult: () -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val zone = ZoneId.systemDefault()
    val next = if (task.enabled) Schedules.next(task, Instant.ofEpochMilli(task.lastRun), zone) else null
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${Schedules.dayLabel(task.days)} at ${Schedules.timeLabel(task.hour, task.minute)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
                Switch(checked = task.enabled, onCheckedChange = onToggle)
            }
            Text(task.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = muted)
            val uses = listOfNotNull(
                if (task.tools) "Uses my tools" else null,
                when (task.notes) {
                    NotesUse.None -> null
                    NotesUse.Recent -> "Reads notes that changed"
                    NotesUse.Search -> "Searches my notes"
                },
            )
            if (uses.isNotEmpty()) Text(uses.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                when {
                    task.lastNote.isNotEmpty() -> "Last run ${whenFormat.format(Instant.ofEpochMilli(task.lastRun).atZone(zone))}: ${task.lastNote}"
                    else -> "Has not run yet"
                } + (next?.let { "\nNext: ${whenFormat.format(it)}" } ?: if (task.enabled) "" else "\nSwitched off"),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (task.lastChat != null) TextButton(onClick = onOpenResult) { Text("Open result") }
                TextButton(onClick = onRunNow) { Text("Run now") }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun SuggestionCard(template: TaskTemplate, missing: Set<ToolId>, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(template.title, style = MaterialTheme.typography.titleSmall)
            Text(template.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (missing.isNotEmpty()) {
                Text(
                    "Needs ${missing.joinToString { it.label }} (off)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
