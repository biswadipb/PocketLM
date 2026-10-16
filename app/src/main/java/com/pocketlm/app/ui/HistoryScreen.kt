package com.pocketlm.app.ui

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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalContext
import com.pocketlm.app.data.ChatExport
import androidx.compose.material3.Card
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

private fun buildRows(chats: List<SavedChat>, query: String, now: Long): List<Row> {
    val q = query.trim()
    val shown = if (q.isEmpty()) chats else chats.filter { c ->
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
    modifier: Modifier = Modifier,
) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var renaming by remember { mutableStateOf<SavedChat?>(null) }
    var saving by remember { mutableStateOf<SavedChat?>(null) }
    val context = LocalContext.current
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val chat = saving
        saving = null
        if (uri != null && chat != null) vm.saveChatTo(uri, chat)
    }
    val rows = remember(chats, query) { buildRows(chats, query, System.currentTimeMillis()) }

    renaming?.let { chat ->
        RenameDialog(
            initial = chat.title,
            onSave = { vm.renameChat(chat, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("History") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        when {
            chats.isEmpty() -> Hint("Your conversations will show up here.")
            rows.isEmpty() -> Hint("No chats match \"${query.trim()}\".")
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
                            onClick = { vm.openChat(row.chat); onOpened() },
                            onPin = { vm.togglePin(row.chat) },
                            onRename = { renaming = row.chat },
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
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatRow(
    chat: SavedChat,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onSaveFile: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${DateUtils.getRelativeTimeSpanString(chat.updatedAt)} · ${chat.messages.size} messages",
                    style = MaterialTheme.typography.bodySmall,
                )
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
