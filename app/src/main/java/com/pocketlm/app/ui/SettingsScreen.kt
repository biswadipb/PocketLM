package com.pocketlm.app.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arm.aichat.GenerationParams
import com.pocketlm.app.PocketViewModel
import kotlin.math.roundToInt

private val CONTEXT_SIZES = listOf(1024, 2048, 4096, 8192, 16384)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: PocketViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    val params by vm.generationParams.collectAsStateWithLifecycle()
    val maxThreads = Runtime.getRuntime().availableProcessors()

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

            Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Generation", Modifier.weight(1f))
                TextButton(onClick = { vm.setGenerationParams(GenerationParams()) }) { Text("Reset") }
            }
            Text(
                "Applies the next time a model loads or you start a new chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                SettingSlider(
                    label = "Temperature",
                    value = params.temperature,
                    valueText = "%.2f".format(params.temperature),
                    hint = "Lower is more focused, higher is more creative.",
                    range = 0f..2f,
                    steps = 39,
                    onChange = { vm.setGenerationParams(params.copy(temperature = (it * 20).roundToInt() / 20f)) },
                )
                SettingSlider(
                    label = "Top-p",
                    value = params.topP,
                    valueText = "%.2f".format(params.topP),
                    hint = "Only sample from the most likely words that add up to this share.",
                    range = 0.1f..1f,
                    steps = 17,
                    onChange = { vm.setGenerationParams(params.copy(topP = (it * 20).roundToInt() / 20f)) },
                )
                val ctxIndex = CONTEXT_SIZES.indexOf(params.contextSize).coerceAtLeast(0)
                SettingSlider(
                    label = "Context length",
                    value = ctxIndex.toFloat(),
                    valueText = "${params.contextSize} tokens",
                    hint = "How much of the conversation the model can remember. Bigger uses more memory.",
                    range = 0f..(CONTEXT_SIZES.lastIndex).toFloat(),
                    steps = CONTEXT_SIZES.size - 2,
                    onChange = { vm.setGenerationParams(params.copy(contextSize = CONTEXT_SIZES[it.roundToInt()])) },
                )
                SettingSlider(
                    label = "CPU threads",
                    value = params.threads.toFloat(),
                    valueText = if (params.threads == 0) "Auto" else params.threads.toString(),
                    hint = "Auto picks a safe number for your phone.",
                    range = 0f..maxThreads.toFloat(),
                    steps = maxThreads - 1,
                    onChange = { vm.setGenerationParams(params.copy(threads = it.roundToInt())) },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    valueText: String,
    hint: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
