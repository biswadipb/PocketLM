package com.pocketlm.app.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.LaunchedEffect
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
import com.pocketlm.app.data.DisplayScale
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.Preset
import java.util.UUID
import com.pocketlm.app.data.MemoryNotes
import com.pocketlm.app.data.OcrScript
import com.pocketlm.app.data.SpeedPreset
import com.pocketlm.app.data.SearchProvider
import com.pocketlm.app.data.Note
import com.pocketlm.app.PocketAssist
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: PocketViewModel,
    onBack: () -> Unit,
    onOpenBenchmark: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenCompare: () -> Unit,
    onOpenLogs: () -> Unit,
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
    val searchProvider by vm.searchProvider.collectAsStateWithLifecycle()
    val braveKey by vm.braveKey.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val ocrScript by vm.ocrScript.collectAsStateWithLifecycle()
    val speedPreset by vm.speedPreset.collectAsStateWithLifecycle()
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    val catalogUpdates by vm.catalogUpdates.collectAsStateWithLifecycle()
    val ocrStatus by vm.ocrStatus.collectAsStateWithLifecycle()
    val saveStates by vm.saveStates.collectAsStateWithLifecycle()
    val autoTitle by vm.autoTitle.collectAsStateWithLifecycle()
    val wikiStatus by vm.wikiStatus.collectAsStateWithLifecycle()
    val autoTags by vm.autoTags.collectAsStateWithLifecycle()
    var editingNote by remember { mutableStateOf<Note?>(null) }
    var addingNote by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val apiEnabled by vm.apiEnabled.collectAsStateWithLifecycle()
    val apiStatus by vm.apiStatus.collectAsStateWithLifecycle()
    val apiPort by vm.apiPort.collectAsStateWithLifecycle()
    val apiHttps by vm.apiHttps.collectAsStateWithLifecycle()
    val apiSwitchModels by vm.apiSwitchModels.collectAsStateWithLifecycle()
    val apiCertificate by vm.apiCertificate.collectAsStateWithLifecycle()
    val apiKey by vm.apiKey.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val presets by vm.presets.collectAsStateWithLifecycle()
    val selectedId by vm.selectedPresetId.collectAsStateWithLifecycle()
    val maxThreads = Runtime.getRuntime().availableProcessors()
    var editing by remember { mutableStateOf<Preset?>(null) }

    if (addingNote || editingNote != null) {
        NoteDialog(
            initial = editingNote?.text.orEmpty(),
            onSave = { text ->
                editingNote?.let { vm.updateNote(it, text) } ?: vm.addNote(text)
                editingNote = null
                addingNote = false
            },
            onDismiss = { editingNote = null; addingNote = false },
        )
    }

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

            val textScale by vm.textScale.collectAsStateWithLifecycle()
            Text("Text size", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = textScale,
                    onValueChange = vm::setTextScale,
                    valueRange = DisplayScale.MIN_TEXT..DisplayScale.MAX_TEXT,
                    steps = Math.round((DisplayScale.MAX_TEXT - DisplayScale.MIN_TEXT) / DisplayScale.STEP) - 1,
                    modifier = Modifier.weight(1f),
                )
                Text(DisplayScale.label(textScale), Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Makes the text in PocketLM bigger or smaller, on top of the size set in your phone's settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Local API server", Modifier.padding(top = 24.dp))
            SwitchRow(
                title = "Let other apps use this model",
                description = "Starts an OpenAI-compatible server on this phone, so tools on your Wi-Fi can chat with the " +
                    "loaded model. Keep PocketLM open with a model loaded.",
                checked = apiEnabled,
                onCheckedChange = vm::setApiEnabled,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (apiEnabled) {
                when (val status = apiStatus) {
                    is ApiStatus.Running -> {
                        val addresses = remember(status) { LocalNetwork.addresses() }
                        Text(
                            if (addresses.isEmpty()) "Running on port ${status.port}, but no Wi-Fi address was found."
                            else "Running. Base URL:\n" + addresses.joinToString("\n") { "${if (status.https) "https" else "http"}://$it:${status.port}/v1" },
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
                SwitchRow(
                    title = "Use HTTPS",
                    description = "Encrypts the connection with a certificate this phone makes for itself.",
                    checked = apiHttps,
                    onCheckedChange = vm::setApiHttps,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (apiHttps) {
                    LaunchedEffect(Unit) { vm.refreshApiCertificate() }
                    apiCertificate?.let { cert ->
                        Text("Certificate fingerprint (SHA-256)", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                        Text(cert.fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Row {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(cert.fingerprint)) }) { Text("Copy fingerprint") }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(cert.pem)) }) { Text("Copy certificate") }
                            TextButton(onClick = vm::resetApiCertificate) { Text("New certificate") }
                        }
                    }
                    Text(
                        "Apps that call the server must trust this certificate: save the copied text as a .crt file and give it " +
                            "to the app (curl: --cacert), or compare the fingerprint. Because the phone makes the certificate itself, " +
                            "browsers and most apps warn about it until you do. It names the addresses this phone has had on your " +
                            "networks, so a new network means a new certificate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SwitchRow(
                    title = "Let requests choose the model",
                    description = "A request that names a downloaded model in its \"model\" field loads it first, which takes a moment and " +
                        "stays loaded afterwards. GET /v1/models then lists every downloaded chat model. Off by default, since loading " +
                        "a big model can use up the phone's memory.",
                    checked = apiSwitchModels,
                    onCheckedChange = vm::setApiSwitchModels,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    if (apiHttps) {
                        "Send the key as an Authorization: Bearer header. Anyone on your network who has the key can use your model."
                    } else {
                        "Send the key as an Authorization: Bearer header. Anyone on your network who has the key can use " +
                            "your model, and the connection is not encrypted, so only use it on a network you trust."
                    },
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

            Text(
                "Bring your old chats along from ChatGPT (Settings, Data controls, Export data) or Claude (Settings, Privacy, " +
                    "Export data). Choose the .zip you were sent, or the conversations.json inside it. Only the text is imported, " +
                    "and the chats are tagged so you can find them.",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val importProgress by vm.importProgress.collectAsStateWithLifecycle()
            val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let(vm::importChats)
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { importPicker.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain")) },
                    enabled = importProgress == null,
                ) { Text("Import from ChatGPT or Claude") }
                importProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }

            SyncSection(vm)

            DocumentSearchSection(vm)
            NotesFolderSection(vm)
            ScheduledPromptsSection(vm)
            AutomationSection(vm)

            SectionTitle("Heat and battery", Modifier.padding(top = 24.dp))
            val autoThrottle by vm.autoThrottle.collectAsStateWithLifecycle()
            SwitchRow(
                title = "Slow down when the phone is hot or low on battery",
                description = "Uses fewer processor threads and shortens replies while the phone is warm or the battery is low and not " +
                    "charging, then goes back to normal. The context length is not changed, as that needs the model to " +
                    "load again.",
                checked = autoThrottle,
                onCheckedChange = vm::setAutoThrottle,
                modifier = Modifier.padding(top = 4.dp),
            )

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

            SwitchRow(
                title = "Save each chat's state",
                description = "Reopening a chat, or waking the model, picks up where you left off instead of reading the whole " +
                    "chat again. Uses storage space (up to about 0.75 GB in all) that Android can take back.",
                checked = saveStates,
                onCheckedChange = vm::setSaveStates,
                modifier = Modifier.padding(top = 12.dp),
            )

            SwitchRow(
                title = "Name new chats",
                description = "After the first reply, the model gives the chat a short title. It takes a moment and the chat can " +
                    "be renamed any time. Chats you renamed are left alone.",
                checked = autoTitle,
                onCheckedChange = vm::setAutoTitle,
                modifier = Modifier.padding(top = 4.dp),
            )
            SwitchRow(
                title = "File them under my tags",
                description = "A newly named chat is also given up to two tags you already use, if they clearly fit. New tags are never invented.",
                checked = autoTags,
                onCheckedChange = vm::setAutoTags,
                enabled = autoTitle,
            )

            SectionTitle("Memory notes", Modifier.padding(top = 24.dp))
            Text(
                "Things the model should always know about you, such as \"I'm vegetarian\" or \"I write Kotlin\". " +
                    "They are added to the system prompt of every chat, and never sent to apps that use the local API server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notes.forEach { note ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The switch and the note are one control, so a screen reader says "I'm vegetarian, switch, on".
                    Row(
                        Modifier.weight(1f).toggleable(value = note.enabled, role = Role.Switch, onValueChange = { vm.setNoteEnabled(note, it) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(checked = note.enabled, onCheckedChange = null)
                        Text(note.text, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = { editingNote = note }) { Icon(Icons.Filled.Edit, contentDescription = "Edit note") }
                    IconButton(onClick = { vm.deleteNote(note) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete note") }
                }
            }
            TextButton(onClick = { addingNote = true }) { Text("Add a note") }

            SectionTitle("Model downloads", Modifier.padding(top = 24.dp))
            SwitchRow(
                title = "Download on Wi-Fi only",
                description = "Models are large. With this on, a download will not start on mobile data, and one in progress pauses " +
                    "(keeping what arrived) if the phone switches to mobile data.",
                checked = wifiOnly,
                onCheckedChange = vm::setWifiOnly,
                modifier = Modifier.padding(top = 4.dp),
            )
            SwitchRow(
                title = "Update the model list from the internet",
                description = "When you open Models, PocketLM reads a small list in its repository on GitHub (at most twice a day) so " +
                    "new models can appear without a new version of the app. GitHub sees your address; nothing else is sent. " +
                    "Only Hugging Face and ModelScope download links in it are accepted.",
                checked = catalogUpdates,
                onCheckedChange = vm::setCatalogUpdates,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Models from Hugging Face are checked against the checksum it publishes when the download finishes; a file " +
                    "that does not match is deleted. Models from other sites, and files you import, are not checked.",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Text recognition", Modifier.padding(top = 24.dp))
            Text(
                "Scanned PDFs and photos are read as text on your phone. Pick the writing system your documents use. " +
                    "Its language data (a few MB) is downloaded once through Google Play services the first time it is " +
                    "needed, and works offline after that. Cyrillic, Arabic, Greek, Hebrew and Thai are not supported yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.selectableGroup().padding(top = 4.dp)) {
                OcrScript.entries.forEach { script ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = script == ocrScript, onClick = { vm.setOcrScript(script) }, role = Role.RadioButton)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = script == ocrScript, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(script.label)
                            Text(script.covers, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            TextButton(onClick = vm::downloadOcrLanguage) { Text("Download ${ocrScript.label} now") }
            ocrStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            SectionTitle("Model tools", Modifier.padding(top = 24.dp))
            Text(
                "Let the model use helpers while it answers. Applies the next time a model loads or you start a new chat. " +
                    "Small models do not always use tools well, so try it and see.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToolId.entries.forEach { tool ->
                SwitchRow(
                    title = tool.label,
                    description = if (tool == ToolId.Wikipedia && wikiStatus.configured && wikiStatus.problem == null) {
                        "Looks up facts in the Wikipedia file on this phone. Nothing is sent anywhere."
                    } else {
                        tool.blurb
                    },
                    checked = tool in enabledTools,
                    onCheckedChange = { vm.setToolEnabled(tool, it) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (ToolId.WebSearch in enabledTools) {
                Text("Search with", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    SearchProvider.entries.forEach { provider ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = provider == searchProvider, onClick = { vm.setSearchProvider(provider) }, role = Role.RadioButton)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = provider == searchProvider, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(provider.label)
                                Text(provider.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                when (searchProvider) {
                    SearchProvider.DuckDuckGo -> Unit
                    SearchProvider.Brave -> OutlinedTextField(
                        value = braveKey,
                        onValueChange = vm::setBraveKey,
                        label = { Text("Brave Search API key") },
                        supportingText = { Text("Stays on this phone: it is not included in backups or sync.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    SearchProvider.SearXNG -> OutlinedTextField(
                        value = searchServer,
                        onValueChange = vm::setSearchServer,
                        label = { Text("Search server address") },
                        placeholder = { Text("search.example.org") },
                        supportingText = { Text("A SearXNG server that allows JSON results, ideally your own.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                Text(
                    "The words the model searches for are sent to ${searchProvider.label}.",
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RemindersSection(vm, toolOn = ToolId.Reminder in enabledTools)

            WikipediaSection(vm)

            SectionTitle("Notifications", Modifier.padding(top = 24.dp))
            SwitchRow(
                title = "Keep replies going in the background",
                description = "A long reply carries on when you switch apps or the screen turns off, and you get a " +
                    "notification when it is ready. A small notification shows while one is being written.",
                checked = backgroundReplies,
                onCheckedChange = vm::setBackgroundReplies,
                modifier = Modifier.padding(top = 4.dp),
            )

            SectionTitle("Voice", Modifier.padding(top = 24.dp))
            SwitchRow(
                title = "Read replies aloud",
                description = "Speak each reply when it finishes, using your phone's voice. " +
                    "You can also tap the speaker on any reply. Code blocks are skipped.",
                checked = readAloud,
                onCheckedChange = vm::setReadAloud,
                modifier = Modifier.padding(top = 4.dp),
            )
            NaturalVoiceSection(vm)
            OfflineDictationSection(vm)

            SectionTitle("Privacy", Modifier.padding(top = 24.dp))
            SwitchRow(
                title = "Require unlock",
                description = "Ask for your fingerprint, face or screen lock when opening the app. " +
                    "Also hides it in recent apps and blocks screenshots.",
                checked = lockEnabled,
                onCheckedChange = vm::setAppLock,
                modifier = Modifier.padding(top = 4.dp),
            )

            SectionTitle("Tools", Modifier.padding(top = 24.dp))
            TextButton(onClick = onOpenBenchmark, Modifier.fillMaxWidth()) {
                Text("Benchmark the loaded model", Modifier.weight(1f))
            }
            TextButton(onClick = onOpenCompare, Modifier.fillMaxWidth()) {
                Text("Compare two models", Modifier.weight(1f))
            }
            TextButton(onClick = onOpenLogs, Modifier.fillMaxWidth()) {
                Text("Diagnostics and logs")
            }
            TextButton(
                onClick = { PocketAssist.openSettings(context) },
                Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (PocketAssist.isDefault(context)) "Default assistant: PocketLM (change)"
                    else "Make PocketLM your default assistant",
                )
            }
            Text(
                "With PocketLM as your assistant you can ask about what is on your screen: use the assistant gesture, and " +
                    "PocketLM opens a chat with the screen's text and a screenshot attached. In the phone's assistant settings, " +
                    "allow it to use text and a screenshot from the screen. The screen is only read when you use the gesture, and " +
                    "stays on the phone. Password fields and apps that block screen capture are left out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                            Text(preset.title)
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
            Text("Preset", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
            Column(Modifier.selectableGroup()) {
                SpeedPreset.entries.forEach { preset ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = preset == speedPreset, onClick = { vm.applySpeedPreset(preset) }, role = Role.RadioButton)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = preset == speedPreset, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(preset.label)
                            Text(preset.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (speedPreset == null) {
                Text(
                    "Custom: your own mix of the settings below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                GenerationControls(params, maxThreads, vm::setGenerationParams)
                SettingSlider(
                    label = "Max reply length",
                    value = maxReply.toFloat(),
                    valueText = "$maxReply tokens",
                    hint = "A reply stops at this length and shows a Continue button. Applies to the next reply.",
                    range = 128f..4096f,
                    steps = 30,
                    onChange = { vm.setMaxReplyTokens(((it / 128).roundToInt() * 128).coerceIn(128, 4096)) },
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
    var greeting by remember { mutableStateOf(initial.greeting) }
    var emoji by remember { mutableStateOf(initial.emoji) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isEmpty()) "New preset or character" else "Edit preset") },
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
                OutlinedTextField(
                    value = greeting,
                    onValueChange = { greeting = it },
                    label = { Text("Greeting (makes it a character)") },
                    supportingText = { Text("What it says first when you start a chat as it. Leave empty for a plain preset.") },
                    minLines = 2,
                    maxLines = 4,
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(4) },
                    label = { Text("Emoji (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(name = name.trim(), prompt = prompt.trim(), greeting = greeting.trim(), emoji = emoji.trim())) },
                enabled = name.isNotBlank() && prompt.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun NoteDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isEmpty()) "New note" else "Edit note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MemoryNotes.MAX_NOTE_CHARS) },
                label = { Text("What should the model remember?") },
                supportingText = { Text("${text.length} / ${MemoryNotes.MAX_NOTE_CHARS}") },
                minLines = 2,
                maxLines = 5,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
