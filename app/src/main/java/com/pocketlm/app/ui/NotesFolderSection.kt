package com.pocketlm.app.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.NotesRules
import com.pocketlm.app.data.NotesScanner

/** Settings for asking questions of a folder of notes from any chat. */
@Composable
internal fun NotesFolderSection(vm: PocketViewModel) {
    val context = LocalContext.current
    val status by vm.notesStatus.collectAsStateWithLifecycle()
    val useByDefault by vm.notesDefault.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { NotesScanner.persistPermission(context, uri) }
            vm.chooseNotesFolder(uri)
        }
    }

    SectionTitle("My notes", Modifier.padding(top = 24.dp))
    Text(
        "Choose a folder of text, Markdown, HTML or PDF files, such as an Obsidian vault or a folder in Google Drive, Dropbox or " +
            "Nextcloud, and chats can find answers in it. PocketLM reads the folder, and adds to it only when you save a reply or " +
            "a chat there; what it learns is kept on this phone and is not synced or backed up. With a search model chosen in " +
            "Document search, passages are matched by meaning as well.",
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
    val folder = status.folderName
    if (folder == null) {
        OutlinedButton(onClick = { picker.launch(null) }, Modifier.padding(top = 8.dp)) { Text("Choose a folder") }
        return
    }
    Text(folder, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge)
    if (status.files > 0) {
        Text(
            NotesRules.statusLine(status.files, status.passages, status.meaning) +
                if (status.updatedAt > 0) ", checked " + DateUtils.getRelativeTimeSpanString(status.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) else "",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
    status.problem?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    status.working?.let {
        Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
    }
    if (status.canSave) {
        Text(
            "Replies and chats you save go into a \"PocketLM\" folder inside it; nothing already there is changed.",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    } else {
        Text(
            "PocketLM can only read this folder. To save replies and chats into it, choose the same folder again and allow editing.",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        TextButton(onClick = { picker.launch(null) }) { Text("Allow saving") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = vm::updateNotes, enabled = status.working == null) { Text("Check for changes") }
        TextButton(onClick = { picker.launch(null) }) { Text("Choose another") }
        TextButton(onClick = vm::forgetNotesFolder) { Text("Stop using") }
    }
    SwitchRow(
        title = "Search my notes in new chats",
        description = "You can switch it for one chat in Chat options.",
        checked = useByDefault,
        onCheckedChange = vm::setNotesDefault,
        modifier = Modifier.padding(top = 4.dp),
    )
}
