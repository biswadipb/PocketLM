package com.pocketlm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.arm.aichat.GenerationParams
import kotlin.math.roundToInt

internal val CONTEXT_SIZES = listOf(1024, 2048, 4096, 8192, 16384)

/** The sliders for how a model writes: used for the app-wide settings and for one model's own settings. */
@Composable
fun GenerationControls(params: GenerationParams, maxThreads: Int, onChange: (GenerationParams) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingSlider(
            label = "Temperature",
            value = params.temperature,
            valueText = "%.2f".format(params.temperature),
            hint = "Lower is more focused, higher is more creative.",
            range = 0f..2f,
            steps = 39,
            onChange = { onChange(params.copy(temperature = (it * 20).roundToInt() / 20f)) },
        )
        SettingSlider(
            label = "Top-p",
            value = params.topP,
            valueText = "%.2f".format(params.topP),
            hint = "Only sample from the most likely words that add up to this share.",
            range = 0.1f..1f,
            steps = 17,
            onChange = { onChange(params.copy(topP = (it * 20).roundToInt() / 20f)) },
        )
        val ctxIndex = CONTEXT_SIZES.indexOf(params.contextSize).coerceAtLeast(0)
        SettingSlider(
            label = "Context length",
            value = ctxIndex.toFloat(),
            valueText = "${params.contextSize} tokens",
            hint = "How much of the conversation the model can remember. Bigger uses more memory.",
            range = 0f..(CONTEXT_SIZES.lastIndex).toFloat(),
            steps = CONTEXT_SIZES.size - 2,
            onChange = { onChange(params.copy(contextSize = CONTEXT_SIZES[it.roundToInt()])) },
        )
        SettingSlider(
            label = "Top-k",
            value = params.topK.toFloat(),
            valueText = if (params.topK == 0) "Off" else params.topK.toString(),
            hint = "Only consider the k most likely words at each step. 0 turns it off.",
            range = 0f..200f,
            steps = 0,
            onChange = { onChange(params.copy(topK = it.roundToInt())) },
        )
        SettingSlider(
            label = "Min-p",
            value = params.minP,
            valueText = "%.2f".format(params.minP),
            hint = "Drop words far less likely than the best one. 0 turns it off.",
            range = 0f..0.5f,
            steps = 49,
            onChange = { onChange(params.copy(minP = (it * 100).roundToInt() / 100f)) },
        )
        SettingSlider(
            label = "Repeat penalty",
            value = params.repeatPenalty,
            valueText = "%.2f".format(params.repeatPenalty),
            hint = "Higher discourages repeating itself. 1.00 turns it off.",
            range = 1f..2f,
            steps = 19,
            onChange = { onChange(params.copy(repeatPenalty = (it * 20).roundToInt() / 20f)) },
        )
        SettingSlider(
            label = "CPU threads",
            value = params.threads.toFloat(),
            valueText = if (params.threads == 0) "Auto" else params.threads.toString(),
            hint = "Auto picks a safe number for your phone.",
            range = 0f..maxThreads.toFloat(),
            steps = maxThreads - 1,
            onChange = { onChange(params.copy(threads = it.roundToInt())) },
        )
        OutlinedTextField(
            value = if (params.seed < 0) "" else params.seed.toString(),
            onValueChange = { text ->
                val digits = text.filter { it.isDigit() }.take(9)
                onChange(params.copy(seed = digits.toIntOrNull() ?: -1))
            },
            label = { Text("Seed") },
            placeholder = { Text("Random") },
            supportingText = { Text("Use the same number to get repeatable replies. Leave empty for random.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun SettingSlider(
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
