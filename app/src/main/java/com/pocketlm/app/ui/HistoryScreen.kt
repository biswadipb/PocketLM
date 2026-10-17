package com.pocketlm.app.ui

import androidx.compose.ui.semantics.Role
import android.text.format.DateUtils
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarBorder
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalContext
import com.pocketlm.app.data.ChatExport
import com.pocketlm.app.data.ChatTags
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.SavedChat
import java.util.Calendar

/** Rows of the history list: a section header or a chat. */
private sealed interface Row {
    data class Header(val label: String) : Row
    data class Chat(val chat: SavedChat) : Row
}

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun sectionFor(chat: SavedChat, today: Long): String = when {
    chat.pinned -> "Pinned"
    chat.updatedAt >= today -> "Today"
    chat.updatedAt >= today - DateUtils.DAY_IN_MILLIS -> "Yesterday"
    else -> "Earlier"
}

private fun buildRows(chats: List<SavedChat>, query: String, tag: String?, now: Long): List<Row> {
    val q = query.trim()
    val tagged = if (tag == null) chats else chats.filter { ChatTags.has(it, tag) }
    val shown = if (q.isEmpty()) tagged else tagged.filter { c ->
        c.title.contains(q, ignoreCase = true) || c.messages.any { it.text.contains(q, ignoreCase = true) }
    }
    val today = startOfDay(now)
    val order = listOf("Pinned", "Today", "Yesterday", "Earlier")
    return shown.groupBy { sectionFor(it, today) }
        .toList()
        .sortedBy { order.indexOf(it.first) }
        .flatMap { (label, group) -> listOf<Row>(Row.Header(label)) + group.map { Row.Chat(it) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    vm: PocketViewModel,
    onBack: () -> Unit,
    onOpened: () -> Unit,
    onOpenStarred: () -> Unit,
    onOpenScheduled: () -> Unit,
    onOpenCustomize: () -> Unit,
    modifier: Modifier = Modifier,
    /** True when History sits beside the chat instead of filling the screen: no back arrow, and the open chat is marked. */
    embedded: Boolean = false,
    selectedId: String? = null,
) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var tagging by remember { mutableStateOf<SavedChat?>(null) }
    var showProjects by remember { mutableStateOf(false) }
    val projects by vm.projects.collectAsStateWithLifecycle()
    val attachStatus by vm.attachStatus.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<SavedChat?>(null) }
    var saving by remember { mutableStateOf<SavedChat?>(null) }
    val context = LocalContext.current
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val chat = saving
        saving = null
        if (uri != null && chat != null) vm.saveChatTo(uri, chat)
    }
    val allTags = remember(chats) { ChatTags.counts(chats) }
    // A filter on a label that no chat carries any more would show an empty list with no way to clear it.
    val activeTag = tagFilter?.takeIf { t -> allTags.any { it.first.equals(t, ignoreCase = true) } }
    val rows = remember(chats, query, activeTag) { buildRows(chats, query, activeTag, System.currentTimeMillis()) }

    tagging?.let { chat ->
        val live = chats.firstOrNull { it.id == chat.id } ?: chat
        TagsDialog(
            tags = live.tags,
            all = allTags,
            onChange = { vm.setTags(live, it) },
            onDismiss = { tagging = null },
        )
    }

    renaming?.let { chat ->
        RenameDialog(
            initial = chat.title,
            onSave = { vm.renameChat(chat, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    if (showProjects) {
        ProjectsDialog(
            projects = projects,
            chats = chats,
            busyReading = attachStatus,
            onCreate = vm::createProject,
            onUpdate = vm::updateProject,
            onDelete = vm::deleteProject,
            onAddDocument = vm::addProjectDocument,
            onRemoveDocument = vm::removeProjectDocument,
            onStartChat = { vm.startChatInProject(it); showProjects = false; onOpened() },
            onOpenChat = { vm.openChat(it); showProjects = false; onOpened() },
            onDismiss = { showProjects = false },
        )
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("History") },
            navigationIcon = {
                if (!embedded) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                IconButton(onClick = { showProjects = true }) {
                    Icon(Icons.Filled.Folder, contentDescription = "Projects")
                }
                IconButton(onClick = onOpenStarred) {
                    Icon(Icons.Outlined.StarBorder, contentDescription = "Starred messages")
                }
                IconButton(onClick = onOpenScheduled) {
                    Icon(Icons.Filled.Schedule, contentDescription = "Scheduled tasks")
                }
                IconButton(onClick = onOpenCustomize) {
                    Icon(Icons.Filled.Tune, contentDescription = "Customize: skills and tools")
                }
            },
        )
        if (chats.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search chats") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )
        }
        if (allTags.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(allTags, key = { it.first.lowercase() }) { (name, count) ->
                    FilterChip(
                        selected = activeTag.equals(name, ignoreCase = true),
                        onClick = { tagFilter = if (activeTag.equals(name, ignoreCase = true)) null else name },
                        label = { Text("$name  $count") },
                    )
                }
            }
        }
        when {
            chats.isEmpty() -> Hint("Your conversations will show up here.")
            rows.isEmpty() -> Hint(
                if (query.isBlank()) "No chats with the tag \"$activeTag\"." else "No chats match \"${query.trim()}\".",
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { if (it is Row.Chat) it.chat.id else (it as Row.Header).label }) { row ->
                    when (row) {
                        is Row.Header -> Text(
                            row.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        is Row.Chat -> ChatRow(
                            row.chat,
                            selected = row.chat.id == selectedId,
                            onClick = { vm.openChat(row.chat); onOpened() },
                            onPin = { vm.togglePin(row.chat) },
                            onRename = { renaming = row.chat },
                            onTags = { tagging = row.chat },
                            onShare = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, row.chat.title)
                                    putExtra(Intent.EXTRA_TEXT, vm.markdownFor(row.chat))
                                }
                                context.startActivity(Intent.createChooser(send, "Share chat"))
                            },
                            onSaveFile = { saving = row.chat; saver.launch(ChatExport.fileName(row.chat.title)) },
                            onDelete = { vm.deleteChat(row.chat) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(
    chat: SavedChat,
    selected: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onTags: () -> Unit,
    onShare: () -> Unit,
    onSaveFile: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${DateUtils.getRelativeTimeSpanString(chat.updatedAt)} · ${chat.messages.size} messages",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (chat.tags.isNotEmpty()) {
                    Text(
                        chat.tags.joinToString("  ") { "#$it" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onPin) {
                Icon(
                    if (chat.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (chat.pinned) "Unpin chat" else "Pin chat",
                    tint = if (chat.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text("Tags") }, onClick = { menuOpen = false; onTags() })
                    DropdownMenuItem(text = { Text("Share") }, onClick = { menuOpen = false; onShare() })
                    DropdownMenuItem(text = { Text("Save as file") }, onClick = { menuOpen = false; onSaveFile() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsDialog(tags: List<String>, all: List<Pair<String, Int>>, onChange: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val suggestions = remember(all, tags, typed) { ChatTags.suggestions(all, tags, typed) }
    val canAdd = ChatTags.normalize(typed) != null && tags.size < ChatTags.MAX_PER_CHAT
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tags.isEmpty()) {
                    Text("No tags yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { tag ->
                            InputChip(
                                selected = true,
                                onClick = { onChange(ChatTags.remove(tags, tag)) },
                                label = { Text(tag) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove tag $tag", modifier = Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(ChatTags.MAX_LENGTH) },
                    singleLine = true,
                    label = { Text("New tag") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(
                            enabled = canAdd,
                            onClick = { onChange(ChatTags.add(tags, typed)); typed = "" },
                        ) { Text("Add") }
                    },
                )
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { tag ->
                            SuggestionChip(
                                onClick = { if (tags.size < ChatTags.MAX_PER_CHAT) onChange(ChatTags.add(tags, tag)) },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
                if (tags.size >= ChatTags.MAX_PER_CHAT) {
                    Text("A chat can have up to ${ChatTags.MAX_PER_CHAT} tags.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
