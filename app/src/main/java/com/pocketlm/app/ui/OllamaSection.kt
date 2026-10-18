package com.pocketlm.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel

/** Download a model by the name it has in the Ollama library. */
@Composable
internal fun OllamaSection(vm: PocketViewModel) {
    val state by vm.ollama.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Type the name a model has on ollama.com/library, such as llama3.2:1b. PocketLM downloads the model file from Ollama's " +
                "registry and checks it against the checksum the registry lists. It runs with llama.cpp, so a model Ollama made in its " +
                "own newer format may not load.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f),
                label = { Text("Ollama model name") },
                placeholder = { Text("llama3.2:1b") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (name.isNotBlank()) vm.lookUpOllama(name) }),
            )
            OutlinedButton(onClick = { vm.lookUpOllama(name) }, enabled = name.isNotBlank() && state != PocketViewModel.OllamaState.Looking) {
                Text("Look up")
            }
        }
        when (val s = state) {
            PocketViewModel.OllamaState.Idle -> {}
            PocketViewModel.OllamaState.Looking -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                Text("Asking the Ollama registry…")
            }
            is PocketViewModel.OllamaState.Failed -> Text(s.message, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
            is PocketViewModel.OllamaState.Found -> Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(s.ref.display, style = MaterialTheme.typography.titleSmall)
                    val model = Formatter.formatShortFileSize(context, s.manifest.model.size)
                    val addOn = s.manifest.projector?.let { Formatter.formatShortFileSize(context, it.size) }
                    Text(
                        "Model file $model" + if (addOn != null) ", and a vision add-on of $addOn" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                    Text("Saved as ${s.fileName}", style = MaterialTheme.typography.bodySmall, color = muted)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.downloadFromOllama() }) { Text("Download") }
                        TextButton(onClick = vm::clearOllama) { Text("Cancel") }
                    }
                }
            }
        }
    }
}
