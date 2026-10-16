package com.pocketlm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.CompareSide
import com.pocketlm.app.ComparePhase
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.LocalModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(vm: PocketViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val models by vm.models.collectAsStateWithLifecycle()
    val sides by vm.compare.collectAsStateWithLifecycle()
    val comparing by vm.comparing.collectAsStateWithLifecycle()
    var pathA by rememberSaveable { mutableStateOf<String?>(null) }
    var pathB by rememberSaveable { mutableStateOf<String?>(null) }
    var prompt by rememberSaveable { mutableStateOf("") }
    val a = models.firstOrNull { it.path == pathA }
    val b = models.firstOrNull { it.path == pathB }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Compare models") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Ask two models the same question. They run one after the other, then your chat's model is put back.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ModelPicker("First model", a, models, enabled = !comparing) { pathA = it.path }
            ModelPicker("Second model", b, models, enabled = !comparing) { pathB = it.path }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Question") },
                minLines = 2,
                maxLines = 5,
                enabled = !comparing,
                modifier = Modifier.fillMaxWidth(),
            )
            if (comparing) {
                OutlinedButton(onClick = vm::cancelCompare, Modifier.fillMaxWidth()) { Text("Stop") }
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Button(
                    onClick = { if (a != null && b != null) vm.runCompare(a, b, prompt) },
                    enabled = a != null && b != null && prompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Compare") }
            }
            sides.forEach { SideCard(it) }
        }
    }
}

@Composable
private fun ModelPicker(label: String, selected: LocalModel?, models: List<LocalModel>, enabled: Boolean, onPick: (LocalModel) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled && models.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(
                selected?.fileName?.removeSuffix(".gguf") ?: if (models.isEmpty()) "No downloaded models" else label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { m ->
                DropdownMenuItem(text = { Text(m.fileName) }, onClick = { onPick(m); open = false })
            }
        }
    }
}

@Composable
private fun SideCard(side: CompareSide) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(side.modelName.removeSuffix(".gguf"), style = MaterialTheme.typography.titleSmall)
            Text(
                when (side.phase) {
                    ComparePhase.Waiting -> "Waiting"
                    ComparePhase.Loading -> "Loading model…"
                    ComparePhase.Generating -> "Writing…"
                    ComparePhase.Done -> "Done"
                    ComparePhase.Failed -> "Failed"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (side.phase == ComparePhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (side.text.isNotEmpty()) {
                MarkdownText(side.text, Modifier.padding(top = 8.dp))
            }
            side.stats?.let {
                Text(
                    statsLine(it),
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
