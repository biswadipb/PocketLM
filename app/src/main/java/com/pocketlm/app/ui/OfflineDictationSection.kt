package com.pocketlm.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.SpeechModels

/** Choosing how dictation hears you: the phone's own recogniser, or a speech model that runs on the phone. */
@Composable
internal fun OfflineDictationSection(vm: PocketViewModel) {
    val chosen by vm.speechModelFile.collectAsStateWithLifecycle()
    val installed by vm.speechModels.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Text("Dictation (the microphone in the message box)", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge)
    Text(
        "By default your phone's own speech recogniser listens, and on many phones it sends the audio to the maker's servers. " +
            "A speech model on the phone writes down what you say without sending anything anywhere. It takes a few seconds " +
            "after you finish speaking, and about 1 GB of storage.",
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
    Column(Modifier.selectableGroup().padding(top = 4.dp)) {
        listOf("" to "The phone's own recogniser").plus(installed.map { it.fileName to it.fileName.removeSuffix(".gguf") }).forEach { (file, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = chosen == file, onClick = { vm.setSpeechModel(file) }, role = Role.RadioButton)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen == file, onClick = null)
                Text(label, Modifier.padding(start = 12.dp))
            }
        }
    }
    SpeechModels.catalog.filter { c -> installed.none { it.fileName == c.fileName } }.forEach { c ->
        val progress = downloads[c.fileName] ?: c.projectorFileName?.let { downloads[it] }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(c.name)
            Text("${c.description}", style = MaterialTheme.typography.bodySmall, color = muted)
            if (progress != null) {
                val value = progress.progress
                if (value == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                else LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            } else {
                OutlinedButton(onClick = { vm.download(c) }) { Text("Download (${c.sizeMb + 205} MB)") }
            }
        }
    }
}
