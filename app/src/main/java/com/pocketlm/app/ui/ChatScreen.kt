package com.pocketlm.app.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import com.pocketlm.app.data.ModelCatalog
import com.pocketlm.app.data.PromptFields
import com.pocketlm.app.data.SavedPrompt
import com.pocketlm.app.data.SourceRef
import com.pocketlm.app.data.Sources
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.MoreVert
import java.time.ZonedDateTime
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.remember
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
import androidx.compose.runtime.mutableStateMapOf
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
import com.pocketlm.app.DictationState
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketlm.app.PocketViewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.mutableIntStateOf
import com.pocketlm.app.data.ChatSearch
import com.pocketlm.app.data.VoiceLoop
import com.pocketlm.app.data.ThrottlePolicy
import com.pocketlm.app.data.ContextMeter
import com.pocketlm.app.data.GenStats
import com.pocketlm.app.data.Thinking
import com.pocketlm.app.data.Presets
import androidx.compose.material3.AssistChip
import com.pocketlm.app.data.PromptRecord
import com.pocketlm.app.data.Variants
import com.pocketlm.app.data.Summaries
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: PocketViewModel,
    onOpenHistory: () -> Unit,
    showHistoryButton: Boolean = true,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val loaded by vm.loadedModel.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val pendingImages by vm.pendingImages.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    val incoming by vm.incomingText.collectAsStateWithLifecycle()
    val dictationState by vm.dictation.collectAsStateWithLifecycle()
    val offlineDictation by vm.offlineDictationReady.collectAsStateWithLifecycle()
    val dictated by vm.dictatedText.collectAsStateWithLifecycle()
    val askRequests by vm.askRequests.collectAsStateWithLifecycle()
    val inputFocus = remember { FocusRequester() }
    val speakingId by vm.speakingId.collectAsStateWithLifecycle()
    val deviceStatus by vm.deviceStatus.collectAsStateWithLifecycle()
    val sleeping by vm.sleeping.collectAsStateWithLifecycle()
    val usage by vm.contextUsage.collectAsStateWithLifecycle()
    val cutOffId by vm.cutOffId.collectAsStateWithLifecycle()
    val attachStatus by vm.attachStatus.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val chatProjectId by vm.chatProjectId.collectAsStateWithLifecycle()
    val chatPresetId by vm.chatPresetId.collectAsStateWithLifecycle()
    val defaultPresetId by vm.selectedPresetId.collectAsStateWithLifecycle()
    val jsonMode by vm.jsonMode.collectAsStateWithLifecycle()
    val jsonSchema by vm.jsonSchema.collectAsStateWithLifecycle()
    val grammar by vm.grammar.collectAsStateWithLifecycle()
    var openedSource by remember { mutableStateOf<SourceRef?>(null) }
    var fillingPrompt by remember { mutableStateOf<SavedPrompt?>(null) }
    val summarising by vm.summarising.collectAsStateWithLifecycle()
    val promptRecord by vm.promptRecord.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }
    var showPromptRecord by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showWebDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val models by vm.models.collectAsStateWithLifecycle()
    val prompts by vm.prompts.collectAsStateWithLifecycle()
    var showPrompts by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) {
            input = if (input.isBlank()) heard else input.trimEnd() + " " + heard
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::attach)
    }
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var matchIndex by rememberSaveable { mutableIntStateOf(0) }
    val noteSuggestions by vm.noteSuggestions.collectAsStateWithLifecycle()
    val incognito by vm.incognito.collectAsStateWithLifecycle()
    val notesStatus by vm.notesStatus.collectAsStateWithLifecycle()
    val useNotes by vm.useNotes.collectAsStateWithLifecycle()
    val thinking by vm.thinking.collectAsStateWithLifecycle()
    val thinkingSupported by vm.thinkingSupported.collectAsStateWithLifecycle()
    val voice by vm.voiceState.collectAsStateWithLifecycle()
    val voicePartial by vm.voicePartial.collectAsStateWithLifecycle()
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoiceMode()
        else vm.reportError("The hands-free conversation needs the microphone. You can allow it in the phone's settings.")
    }
    // The microphone is only used while the chat is on screen, and the screen stays on during a conversation.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopVoiceMode() }
    val view = LocalView.current
    val voiceActive = voice.phase != VoiceLoop.Phase.Off
    DisposableEffect(voiceActive) {
        view.keepScreenOn = voiceActive
        onDispose { view.keepScreenOn = false }
    }
    val listState = rememberLazyListState()
    val matches = remember(messages, searchQuery, searching) {
        if (searching) ChatSearch.find(messages.map { it.text }, searchQuery) else emptyList()
    }
    val currentMatchNumber = matchIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0))
    val currentMatch = matches.getOrNull(currentMatchNumber) ?: -1
    LaunchedEffect(currentMatch, searchQuery) {
        if (currentMatch >= 0) listState.animateScrollToItem(currentMatch)
    }
    // Opening a starred message from the list scrolls to it.
    val scrollTarget by vm.scrollTarget.collectAsStateWithLifecycle()
    LaunchedEffect(scrollTarget, messages.size) {
        scrollTarget?.let { target ->
            if (target in messages.indices) {
                listState.scrollToItem(target)
                vm.consumeScrollTarget()
            }
        }
    }

    LaunchedEffect(askRequests) {
        if (askRequests > 0) runCatching { inputFocus.requestFocus() }
    }

    LaunchedEffect(incoming) {
        incoming?.let {
            input = if (input.isBlank()) it else input + "\n\n" + it
            vm.consumeIncomingText()
        }
    }

    // Words written down from your voice by the speech model on the phone.
    LaunchedEffect(dictated) {
        dictated?.let {
            input = if (input.isBlank()) it else input.trimEnd() + " " + it
            vm.consumeDictatedText()
        }
    }
    val dictationMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleDictation()
        else vm.reportError("Dictation needs the microphone. You can allow it in the phone's settings.")
    }

    if (showOptions) {
        ChatOptionsDialog(
            models = models,
            loaded = loaded,
            presets = presets,
            chatPresetId = chatPresetId,
            defaultPresetId = defaultPresetId,
            jsonMode = jsonMode,
            jsonSchema = jsonSchema,
            grammar = grammar,
            thinkingSupported = thinkingSupported,
            thinking = thinking,
            onThinking = vm::setChatThinking,
            notesFolder = notesStatus.folderName,
            useNotes = useNotes,
            onUseNotes = vm::setUseNotes,
            projects = projects,
            projectId = chatProjectId,
            onProject = vm::setChatProject,
            onModel = vm::switchModel,
            onPreset = vm::setChatPreset,
            onJsonMode = vm::setJsonMode,
            onJsonSchema = vm::setJsonSchema,
            onGrammar = vm::setGrammar,
            onSaveToNotes = if (notesStatus.folderName == null || messages.isEmpty() || incognito) null else ({
                showOptions = false
                vm.saveChatToNotes()
            }),
            canSummarise = messages.size >= 2 && loaded != null && !generating && !loading,
            onSummarise = { showOptions = false; vm.summariseChat() },
            onSuggestNotes = { showOptions = false; vm.suggestNotes() },
            onShowPrompt = if (promptRecord == null) null else ({ showOptions = false; showPromptRecord = true }),
            onShareChat = if (messages.isEmpty()) null else ({
                vm.currentChatMarkdown()?.let { md ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, md)
                    }
                    context.startActivity(Intent.createChooser(send, "Share chat"))
                }
            }),
            onDismiss = { showOptions = false },
        )
    }

    when (val state = noteSuggestions) {
        PocketViewModel.NoteSuggestionState.Idle, PocketViewModel.NoteSuggestionState.Working -> {}
        is PocketViewModel.NoteSuggestionState.Ready -> NoteSuggestionsDialog(
            suggestions = state.suggestions,
            onAdd = vm::addSuggestedNotes,
            onDismiss = vm::dismissNoteSuggestions,
        )
    }

    if (showPromptRecord) {
        promptRecord?.let { PromptInspectorDialog(it, onDismiss = { showPromptRecord = false }) }
    }

    if (showWebDialog) {
        WebPageDialog(
            onFetch = { vm.attachWebPage(it); showWebDialog = false },
            onDismiss = { showWebDialog = false },
        )
    }

    val clipboardManager = LocalClipboardManager.current
    /** Puts a saved prompt in the message box with its fields filled in. */
    fun insertPrompt(prompt: SavedPrompt, answers: Map<String, String>) {
        // The clipboard is only read when the prompt asks for it.
        val clip = if (PromptFields.usesClipboard(prompt.text)) clipboardManager.getText()?.text.orEmpty() else ""
        val text = PromptFields.fill(prompt.text, answers, clip, ZonedDateTime.now())
        input = if (input.isBlank()) text else input + "\n" + text
    }
    fillingPrompt?.let { prompt ->
        FillPromptDialog(
            asks = PromptFields.asks(prompt.text),
            title = prompt.title,
            onDone = { insertPrompt(prompt, it); fillingPrompt = null },
            onDismiss = { fillingPrompt = null },
        )
    }
    openedSource?.let { SourceDialog(it, onDismiss = { openedSource = null }) }

    if (showPrompts) {
        PromptLibraryDialog(
            prompts = prompts,
            currentText = input,
            onInsert = { prompt ->
                showPrompts = false
                if (PromptFields.asks(prompt.text).isEmpty()) insertPrompt(prompt, emptyMap()) else fillingPrompt = prompt
            },
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

    // A screen reader is told when a reply starts and when it is ready, without reading every word as it arrives.
    var announcement by remember { mutableStateOf("") }
    var sawGenerating by remember { mutableStateOf(false) }
    LaunchedEffect(generating) {
        if (generating) {
            sawGenerating = true
            announcement = "Piko is writing a reply"
        } else if (sawGenerating) {
            announcement = "Reply ready"
        }
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
        TopAppBar(
            title = { Text(loaded?.fileName?.removeSuffix(".gguf") ?: "PocketLM") },
            actions = {
                if (loaded != null) {
                    IconButton(onClick = vm::newChat, enabled = !generating && !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "New chat")
                    }
                    IconButton(onClick = vm::startIncognitoChat, enabled = !generating && !loading) {
                        Icon(
                            Icons.Filled.VisibilityOff,
                            contentDescription = "New incognito chat",
                            tint = if (incognito) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                }
                if (messages.isNotEmpty()) {
                    IconButton(onClick = { searching = !searching; if (!searching) searchQuery = "" }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search this chat")
                    }
                }
                IconButton(onClick = { showOptions = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Chat options")
                }
                if (showHistoryButton) {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "History")
                    }
                }
                IconButton(onClick = onOpenModels) {
                    Icon(Icons.Filled.Hub, contentDescription = "Models")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            },
        )

        if (searching && messages.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it; matchIndex = 0 },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Find in this chat") },
                    singleLine = true,
                    trailingIcon = {
                        Text(
                            if (searchQuery.isBlank()) "" else if (matches.isEmpty()) "none" else "${currentMatchNumber + 1} of ${matches.size}",
                            Modifier.padding(end = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
                IconButton(
                    onClick = { matchIndex = ChatSearch.step(matchIndex, matches.size, forward = false) },
                    enabled = matches.size > 1,
                ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match") }
                IconButton(
                    onClick = { matchIndex = ChatSearch.step(matchIndex, matches.size, forward = true) },
                    enabled = matches.size > 1,
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match") }
                IconButton(onClick = { searching = false; searchQuery = "" }) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
            }
        }
        if (incognito) {
            Surface(color = MaterialTheme.colorScheme.inverseSurface, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Incognito chat. Nothing here is saved, synced or added to History, and it is erased when you leave it.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
        val indexStatus by vm.indexStatus.collectAsStateWithLifecycle()
        (attachStatus ?: indexStatus)?.let { status ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        if (sleeping) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "The model was unloaded to free memory. It loads again when you send a message.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        val throttle by vm.throttle.collectAsStateWithLifecycle()
        listOfNotNull(deviceStatus.warning, ThrottlePolicy.note(throttle, deviceStatus)).takeIf { it.isNotEmpty() }?.joinToString(" ")?.let { warning ->
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    warning,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        val proposedNote by vm.proposedNote.collectAsStateWithLifecycle()
        proposedNote?.let { fact ->
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Piko suggests remembering:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(fact, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = vm::dismissProposedNote) { Text("No thanks") }
                        TextButton(onClick = vm::acceptProposedNote) { Text("Save") }
                    }
                }
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
                            speaking = speakingId == message.id,
                            onSpeak = { if (speakingId == message.id) vm.stopSpeaking() else vm.speak(message) },
                            canContinue = message.id == cutOffId && !generating && !loading,
                            onContinue = vm::continueReply,
                            canEdit = message.isUser && !generating && !loading && loaded != null,
                            onEdit = { editing = message },
                            canRegenerate = message.id == messages.lastOrNull()?.id &&
                                !message.isUser && !message.summary && !generating && !loading && messages.size >= 2,
                            onRegenerate = vm::regenerate,
                            canPage = message.id == messages.lastOrNull()?.id && !generating && !loading,
                            onPage = { target -> vm.selectReply(message.id, target) },
                            highlighted = searching && currentMatch == messages.indexOf(message),
                            onStar = { vm.toggleStar(message.id) },
                            onBranch = if (message.text.isEmpty() || message.summary || generating || loading || incognito || loaded == null ||
                                (message.isUser && messages.indexOf(message) == 0)
                            ) null else ({ vm.branchFrom(message.id) }),
                            onSaveToNotes = if (message.isUser || message.summary || message.text.isEmpty() || notesStatus.folderName == null || incognito) null
                            else ({ vm.saveReplyToNotes(message.id) }),
                            onSource = { openedSource = it },
                        )
                    }
                }
            }
            val characters = remember(presets) { Presets.characters(presets) }
            if (loaded != null && !loading && messages.isEmpty() && characters.isNotEmpty()) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Or start a chat as…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        characters.forEach { character ->
                            AssistChip(onClick = { vm.startChatAs(character) }, label = { Text(character.title) })
                        }
                    }
                }
            }
        }

        if (pendingImages.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pendingImages.forEach { path ->
                    Box {
                        Thumbnail(
                            path,
                            Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        IconButton(
                            onClick = { vm.removePendingImage(path) },
                            modifier = Modifier.align(Alignment.TopEnd).size(48.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove image",
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                    .padding(4.dp)
                                    .size(16.dp),
                            )
                        }
                    }
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
                        onClick = { vm.removeAttachment(doc) },
                        label = { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                        leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.semantics { contentDescription = "Attached: ${doc.name}. Double tap to remove it." },
                    )
                }
            }
        }
        if (dictationState != DictationState.Idle) {
            Text(
                if (dictationState == DictationState.Listening) "Listening… tap the red square when you have finished."
                else "Writing down what you said…",
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (usage.total > 0) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                LinearProgressIndicator(
                    progress = { ContextMeter.fraction(usage) },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (ContextMeter.level(usage) == ContextMeter.Level.Fine) MaterialTheme.colorScheme.primary
                    else if (ContextMeter.level(usage) == ContextMeter.Level.Filling) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error,
                )
                Text(
                    ContextMeter.warning(usage) ?: ContextMeter.label(usage),
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (Summaries.worthOffering(usage, messages.size) && !generating && !loading && !summarising) {
                    TextButton(onClick = vm::summariseChat) { Text("Summarise and continue") }
                }
            }
        }
        if (voice.phase != VoiceLoop.Phase.Off) VoicePanel(voice.phase, voicePartial, onEnd = vm::stopVoiceMode) else Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(
                    onClick = { showAttachMenu = true },
                    enabled = loaded != null && !generating && !loading,
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "Attach a file, photo or web page")
                }
                DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("File or photo") },
                        onClick = { showAttachMenu = false; picker.launch(arrayOf("*/*")) },
                    )
                    DropdownMenuItem(
                        text = { Text("Web page link") },
                        onClick = { showAttachMenu = false; showWebDialog = true },
                    )
                }
            }
            IconButton(onClick = { showPrompts = true }, enabled = !generating && !loading) {
                Icon(Icons.Filled.Bookmarks, contentDescription = "Saved prompts")
            }
            IconButton(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        vm.startVoiceMode()
                    } else {
                        microphone.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = loaded != null && !generating && !loading,
            ) { Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Talk hands-free") }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).focusRequester(inputFocus),
                placeholder = { Text(if (loaded == null) "Load a model first" else "Message Piko") },
                enabled = loaded != null && !generating && !loading,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                trailingIcon = {
                    if ((input.isEmpty() || dictationState != DictationState.Idle) && loaded != null && !generating && !loading) {
                        when (dictationState) {
                            DictationState.Transcribing -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            DictationState.Listening -> IconButton(onClick = vm::toggleDictation) {
                                Icon(Icons.Filled.Stop, contentDescription = "Finish dictating", tint = MaterialTheme.colorScheme.error)
                            }
                            DictationState.Idle -> IconButton(onClick = {
                                if (offlineDictation) {
                                    // The speech model on the phone writes down what you say; nothing leaves the phone.
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        vm.toggleDictation()
                                    } else {
                                        dictationMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                } else {
                                    val ask = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
                                    }
                                    try {
                                        dictation.launch(ask)
                                    } catch (e: ActivityNotFoundException) {
                                        vm.reportError("No speech recognition app is installed on this phone.")
                                    }
                                }
                            }) { Icon(Icons.Filled.Mic, contentDescription = "Dictate a message") }
                        }
                    }
                },
            )
            if (generating) {
                IconButton(onClick = vm::stopGeneration) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(
                    onClick = { vm.send(input); input = "" },
                    enabled = loaded != null && !loading && (input.isNotBlank() || pendingImages.isNotEmpty()),
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
    speaking: Boolean,
    onSpeak: () -> Unit,
    canContinue: Boolean,
    onContinue: () -> Unit,
    canEdit: Boolean,
    onEdit: () -> Unit,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    canPage: Boolean,
    onPage: (Int) -> Unit,
    highlighted: Boolean,
    onStar: () -> Unit,
    onBranch: (() -> Unit)?,
    onSaveToNotes: (() -> Unit)?,
    onSource: (SourceRef) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var showMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    if (message.summary) {
        SummaryCard(message.text, onCopy = { clipboard.setText(AnnotatedString(message.text)) })
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
    ) {
        val actions = buildList {
            if (message.text.isNotEmpty()) {
                add(CustomAccessibilityAction("Copy message") { clipboard.setText(AnnotatedString(if (message.isUser) message.text else Thinking.answerOnly(message.text))); true })
                add(CustomAccessibilityAction(if (speaking) "Stop reading" else "Read aloud") { onSpeak(); true })
                add(CustomAccessibilityAction(if (message.starred) "Remove star" else "Star this message") { onStar(); true })
            }
            if (canEdit) add(CustomAccessibilityAction("Edit message") { onEdit(); true })
            if (canRegenerate) add(CustomAccessibilityAction("Regenerate reply") { onRegenerate(); true })
            if (canContinue) add(CustomAccessibilityAction("Continue reply") { onContinue(); true })
            if (onBranch != null) add(CustomAccessibilityAction("Branch from here") { onBranch(); true })
            if (onSaveToNotes != null) add(CustomAccessibilityAction("Save to my notes") { onSaveToNotes(); true })
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (message.isUser) scheme.primaryContainer else scheme.surfaceVariant,
            border = if (highlighted) BorderStroke(2.dp, scheme.primary) else null,
            modifier = Modifier.fillMaxWidth(0.85f).semantics {
                contentDescription = if (message.isUser) "Your message" else "Piko's reply"
                customActions = actions
            },
        ) {
            if (message.text.isEmpty()) {
                CircularProgressIndicator(Modifier.padding(14.dp).semantics { contentDescription = "Writing a reply" }, strokeWidth = 2.dp)
            } else {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (message.images.size > 1) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            message.images.forEach {
                                Thumbnail(it, Modifier.heightIn(max = 120.dp).widthIn(max = 160.dp).clip(RoundedCornerShape(12.dp)))
                            }
                        }
                    } else {
                        message.imagePath?.let {
                            Thumbnail(
                                it,
                                Modifier.padding(bottom = 8.dp).heightIn(max = 180.dp).clip(RoundedCornerShape(12.dp)),
                            )
                        }
                    }
                    if (message.isUser) {
                        Text(message.text)
                    } else {
                        val parts = remember(message.text) { Thinking.split(message.text) }
                        parts.thinking?.let { ThinkingSection(message.id, it, parts.thinkingOpen) }
                        if (parts.answer.isNotBlank()) MarkdownText(parts.answer)
                    }
                    if (canEdit) {
                        IconButton(onClick = onEdit, modifier = Modifier.align(Alignment.End)) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit message")
                        }
                    }
                    if (message.sources.isNotEmpty()) {
                        val labels = remember(message.sources) { Sources.labels(message.sources) }
                        Text(
                            "Looked at",
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            message.sources.forEachIndexed { i, ref ->
                                AssistChip(
                                    onClick = { onSource(ref) },
                                    label = { Text(labels[i], maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                                    leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                )
                            }
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
                        IconButton(onClick = { clipboard.setText(AnnotatedString(if (message.isUser) message.text else Thinking.answerOnly(message.text))) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy message")
                        }
                        IconButton(onClick = onSpeak) {
                            Icon(
                                if (speaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (speaking) "Stop reading" else "Read aloud",
                            )
                        }
                        IconButton(onClick = onStar) {
                            Icon(
                                if (message.starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (message.starred) "Remove star" else "Star this message",
                                tint = if (message.starred) scheme.primary else LocalContentColor.current,
                            )
                        }
                        if (canContinue) {
                            TextButton(onClick = onContinue) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text(" Continue")
                            }
                        }
                        if (canRegenerate) {
                            IconButton(onClick = onRegenerate) {
                                Icon(Icons.Filled.Replay, contentDescription = "Regenerate reply")
                            }
                        }
                        if (onBranch != null || onSaveToNotes != null) {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "More actions for this message")
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    if (onBranch != null) {
                                        DropdownMenuItem(
                                            text = { Text("Branch from here") },
                                            onClick = { showMenu = false; onBranch() },
                                        )
                                    }
                                    if (onSaveToNotes != null) {
                                        DropdownMenuItem(
                                            text = { Text("Save to my notes") },
                                            onClick = { showMenu = false; onSaveToNotes() },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (canPage && message.others.isNotEmpty()) {
                        val count = Variants.count(message.others)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onPage(message.position - 1) }, enabled = message.position > 0) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Earlier version of this reply")
                            }
                            Text("${message.position + 1} / $count", style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = { onPage(message.position + 1) }, enabled = message.position < count - 1) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Later version of this reply")
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
    onInsert: (SavedPrompt) -> Unit,
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
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Insert this prompt") { onInsert(p) }.heightIn(min = 48.dp).padding(vertical = 8.dp),
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
                    Text(
                        PromptFields.HELP,
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

/** The short summary that stands in for the messages above it when the model carries on. */
@Composable
private fun SummaryCard(text: String, onCopy: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                "Summary of the conversation up to here",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (text.isEmpty()) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  Summarising…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(text, Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    "The model carries on from this summary. The messages above stay here for you to read.",
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy summary") }
            }
        }
    }
}

@Composable
private fun WebPageDialog(onFetch: (String) -> Unit, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach a web page") },
        text = {
            Column {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Web address") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                )
                Text(
                    "PocketLM downloads the page from this phone and keeps its text with the chat, like an attached file. " +
                        "The site can see your request, as with any link you open.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onFetch(address) }, enabled = address.isNotBlank()) { Text("Attach") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shows exactly what the model was given for the latest reply, to help work out why it answered as it did. */
@Composable
private fun PromptInspectorDialog(record: PromptRecord, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val text = remember(record) { record.asText() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What the model received") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") } },
    )
}

/** Shown in place of the message box while a hands-free conversation is going. */
@Composable
private fun VoicePanel(phase: VoiceLoop.Phase, partial: String, onEnd: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (phase) {
                        VoiceLoop.Phase.Listening -> "Listening…"
                        VoiceLoop.Phase.Thinking -> "Thinking…"
                        VoiceLoop.Phase.Speaking -> "Speaking…"
                        VoiceLoop.Phase.Off -> ""
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    if (phase == VoiceLoop.Phase.Listening && partial.isNotBlank()) partial
                    else "Say \"stop\" or tap End to finish.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 3,
                )
            }
            Button(onClick = onEnd) { Text("End") }
        }
    }
}

/** Lets the user choose which of the notes the model suggested to keep. */
@Composable
private fun NoteSuggestionsDialog(suggestions: List<String>, onAdd: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember(suggestions) { mutableStateOf(suggestions.toSet()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (suggestions.isEmpty()) "Nothing to remember" else "Remember these?") },
        text = {
            Column {
                if (suggestions.isEmpty()) {
                    Text("The model found no lasting facts about you in this chat, or you already have them as notes.")
                } else {
                    Text(
                        "Tick the ones to keep. They are added to every chat's instructions until you remove them in Settings, Memory notes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    suggestions.forEach { text ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            androidx.compose.material3.Checkbox(
                                checked = text in chosen,
                                onCheckedChange = { on -> chosen = if (on) chosen + text else chosen - text },
                            )
                            Text(text, Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (suggestions.isEmpty()) TextButton(onClick = onDismiss) { Text("OK") }
            else TextButton(onClick = { onAdd(suggestions.filter { it in chosen }) }, enabled = chosen.isNotEmpty()) { Text("Add") }
        },
        dismissButton = { if (suggestions.isNotEmpty()) TextButton(onClick = onDismiss) { Text("None") } },
    )
}

/** Asks for the answers a saved prompt needs (its {{ask: …}} fields) before it goes into the message box. */
@Composable
private fun FillPromptDialog(
    asks: List<PromptFields.Ask>,
    title: String,
    onDone: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val answers = remember { mutableStateMapOf<String, String>().apply { asks.forEach { put(it.label, it.suggestion) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                asks.forEach { ask ->
                    OutlinedTextField(
                        value = answers[ask.label].orEmpty(),
                        onValueChange = { answers[ask.label] = it },
                        label = { Text(ask.label) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(answers.toMap()) }) { Text("Insert") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A passage the model was shown, with where it came from. */
@Composable
private fun SourceDialog(source: SourceRef, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Sources.shortName(source.name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    (if (source.fromNotes) "From your notes: " else "From an attached document: ") + source.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(source.excerpt, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "This is a passage the model was shown with your question. It may not have used all of it.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(source.excerpt)) }) { Text("Copy") } },
    )
}
