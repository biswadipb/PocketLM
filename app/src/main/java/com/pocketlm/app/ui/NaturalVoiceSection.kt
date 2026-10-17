package com.pocketlm.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.VoiceModels

/** Settings for Piko's own voice: a text-to-speech model on the phone, in place of the phone's voice. */
@Composable
internal fun NaturalVoiceSection(vm: PocketViewModel) {
    val on by vm.naturalVoice.collectAsStateWithLifecycle()
    val ready by vm.naturalVoiceReady.collectAsStateWithLifecycle()
    val language by vm.voiceLanguage.collectAsStateWithLifecycle()
    val sample by vm.voiceSample.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val voice = VoiceModels.catalog.first()
    val progress = downloads[voice.fileName] ?: voice.projectorFileName?.let { downloads[it] }
    var choosing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::setVoiceSample) }

    SwitchRow(
        title = "Natural voice (experimental)",
        description = "Read replies aloud with a speech model on the phone instead of the phone's own voice. It needs about 1.5 GB " +
            "of storage and plenty of memory while it speaks, and it is slow: on a fast laptop processor it made speech at about a " +
            "third of real time, so on a phone expect to wait a minute or more for each sentence. Treat it as a demonstration for now; " +
            "a reply is spoken a sentence at a time. It has not been tried on a real phone.",
        checked = on,
        onCheckedChange = vm::setNaturalVoice,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (!on) return

    if (progress != null) {
        Text("Downloading the voice…", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        val value = progress.progress
        if (value != null) LinearProgressIndicator(progress = { value }, Modifier.fillMaxWidth().padding(top = 4.dp))
        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        return
    }
    if (!ready) {
        Button(onClick = vm::downloadNaturalVoice, Modifier.padding(top = 4.dp)) { Text("Download the voice (about 1.5 GB)") }
        Text(
            "Until it is downloaded, the phone's own voice is used. The model is Qwen3-TTS from ggml-org on Hugging Face.",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        return
    }

    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { choosing = true }) {
                Text("Language: " + (VoiceModels.languages.firstOrNull { it.first == language }?.second ?: language))
            }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                VoiceModels.languages.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { vm.setVoiceLanguage(code); choosing = false })
                }
            }
        }
        Button(onClick = vm::tryNaturalVoice) { Text("Try it") }
    }
    if (sample == null) {
        OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, Modifier.padding(top = 8.dp)) { Text("Copy a voice from a recording") }
        Text(
            "Optional: pick a few seconds of clear speech (WAV or MP3) and the voice will imitate it. Only use a recording of " +
                "your own voice, or one you have permission to use.",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    } else {
        Text("Imitating $sample", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Choose another") }
            TextButton(onClick = vm::clearVoiceSample) { Text("Use the model's own voice") }
        }
    }
    TextButton(onClick = vm::deleteNaturalVoice) { Text("Delete the voice files") }
}
