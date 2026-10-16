package com.pocketlm.app.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import com.pocketlm.app.data.ModelCatalog
import com.pocketlm.app.data.SavedPrompt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.InputChip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.ChatMessage
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.GenStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: PocketViewModel,
    onOpenHistory: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val loaded by vm.loadedModel.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val pendingImage by vm.pendingImage.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    val incoming by vm.incomingText.collectAsStateWithLifecycle()
    val askRequests by vm.askRequests.collectAsStateWithLifecycle()
    val inputFocus = remember { FocusRequester() }
    val deviceStatus by vm.deviceStatus.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val chatPresetId by vm.chatPresetId.collectAsStateWithLifecycle()
    val defaultPresetId by vm.selectedPresetId.collectAsStateWithLifecycle()
    val jsonMode by vm.jsonMode.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }
    val models by vm.models.collectAsStateWithLifecycle()
    val prompts by vm.prompts.collectAsStateWithLifecycle()
    var showPrompts by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::attach)
    }
    val listState = rememberLazyListState()

    LaunchedEffect(askRequests) {
        if (askRequests > 0) runCatching { inputFocus.requestFocus() }
    }

    LaunchedEffect(incoming) {
        incoming?.let {
            input = if (input.isBlank()) it else input + "\n\n" + it
            vm.consumeIncomingText()
        }
    }

    if (showOptions) {
        ChatOptionsDialog(
            models = models,
            loaded = loaded,
            presets = presets,
            chatPresetId = chatPresetId,
            defaultPresetId = defaultPresetId,
            jsonMode = jsonMode,
            onModel = vm::switchModel,
            onPreset = vm::setChatPreset,
            onJsonMode = vm::setJsonMode,
            onDismiss = { showOptions = false },
        )
    }

    if (showPrompts) {
        PromptLibraryDialog(
            prompts = prompts,
            currentText = input,
            onInsert = { input = if (input.isBlank()) it else input + "\n" + it; showPrompts = false },
            onSave = vm::savePrompt,
            onDelete = vm::deletePrompt,
            onDismiss = { showPrompts = false },
        )
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    editing?.let { message ->
        EditMessageDialog(
            initial = message.text,
            onSend = { vm.editMessage(message.id, it); editing = null },
            onDismiss = { editing = null },
        )
    }

    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(loaded?.fileName?.removeSuffix(".gguf") ?: "PocketLM") },
            actions = {
                if (loaded != null) {
                    IconButton(onClick = vm::newChat, enabled = !generating && !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "New chat")
                    }
                }
                IconButton(onClick = { showOptions = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Chat options")
                }
                IconButton(onClick = onOpenHistory) {
                    Icon(Icons.Filled.History, contentDescription = "History")
                }
                IconButton(onClick = onOpenModels) {
                    Icon(Icons.Filled.Hub, contentDescription = "Models")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            },
        )

        deviceStatus.warning?.let { warning ->
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    warning,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Loading model…", Modifier.padding(top = 12.dp))
                }
                loaded == null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No model loaded", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Download or import a model to start chatting. Everything runs on your phone.",
                        Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (models.isEmpty()) {
                        Button(
                            onClick = { vm.download(ModelCatalog.models.first()); onOpenModels() },
                            Modifier.padding(top = 8.dp),
                        ) { Text("Download a starter model (${ModelCatalog.models.first().sizeMb} MB)") }
                        Text(
                            "${ModelCatalog.models.first().name}: small and quick, a good first try.",
                            Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = onOpenModels) { Text(if (models.isEmpty()) "Browse other models" else "Choose a model") }
                }
                else -> LazyColumn(
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(
                            message,
                            canEdit = message.isUser && !generating && !loading && loaded != null,
                            onEdit = { editing = message },
                            canRegenerate = message.id == messages.lastOrNull()?.id &&
                                !message.isUser && !generating && !loading && messages.size >= 2,
                            onRegenerate = vm::regenerate,
                        )
                    }
                }
            }
        }

        pendingImage?.let { path ->
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Thumbnail(
                    path,
                    Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
                IconButton(
                    onClick = vm::removePendingImage,
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove image", modifier = Modifier.size(16.dp))
                }
            }
        }
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { doc ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                        leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove ${doc.name}",
                                modifier = Modifier.size(18.dp).clickable { vm.removeAttachment(doc) },
                            )
                        },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = loaded != null && !generating && !loading,
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach a text file or image")
            }
            IconButton(onClick = { showPrompts = true }, enabled = !generating && !loading) {
                Icon(Icons.Filled.Bookmarks, contentDescription = "Saved prompts")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).focusRequester(inputFocus),
                placeholder = { Text(if (loaded == null) "Load a model first" else "Message") },
                enabled = loaded != null && !generating && !loading,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
            )
            if (generating) {
                IconButton(onClick = vm::stopGeneration) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(
                    onClick = { vm.send(input); input = "" },
                    enabled = loaded != null && !loading && (input.isNotBlank() || pendingImage != null),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    canEdit: Boolean,
    onEdit: () -> Unit,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (message.isUser) scheme.primaryContainer else scheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            if (message.text.isEmpty()) {
                CircularProgressIndicator(Modifier.padding(14.dp), strokeWidth = 2.dp)
            } else {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    message.imagePath?.let {
                        Thumbnail(
                            it,
                            Modifier.padding(bottom = 8.dp).heightIn(max = 180.dp).clip(RoundedCornerShape(12.dp)),
                        )
                    }
                    if (message.isUser) Text(message.text) else MarkdownText(message.text)
                    if (canEdit) {
                        IconButton(onClick = onEdit, modifier = Modifier.align(Alignment.End)) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit message")
                        }
                    }
                    message.stats?.let {
                        Text(
                            statsLine(it),
                            Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy message")
                        }
                        if (canRegenerate) {
                            IconButton(onClick = onRegenerate) {
                                Icon(Icons.Filled.Replay, contentDescription = "Regenerate reply")
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun statsLine(s: GenStats): String = buildList {
    s.tokensPerSecond?.let { add("%.1f tok/s".format(it)) }
    add("${s.tokens} tokens")
    add("%.1f s to first token".format(s.firstTokenMs / 1000f))
}.joinToString(" · ")

@Composable
private fun EditMessageDialog(initial: String, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 2, maxLines = 8)
                Text(
                    "The model answers again, and everything after this message is removed.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank()) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PromptLibraryDialog(
    prompts: List<SavedPrompt>,
    currentText: String,
    onInsert: (String) -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: (SavedPrompt) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved prompts") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                prompts.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onInsert(p.text) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                p.text.trim(),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!p.builtIn) {
                            IconButton(onClick = { onDelete(p) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Delete ${p.title}")
                            }
                        }
                    }
                }
                if (currentText.isNotBlank()) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Save the message box as…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    TextButton(
                        onClick = { onSave(title, currentText); title = "" },
                        enabled = title.isNotBlank(),
                    ) { Text("Save prompt") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
