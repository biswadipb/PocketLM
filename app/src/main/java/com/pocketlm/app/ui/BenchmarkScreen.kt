package com.pocketlm.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(vm: PocketViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val results by vm.benchmarks.collectAsStateWithLifecycle()
    val running by vm.benchmarking.collectAsStateWithLifecycle()
    val loaded by vm.loadedModel.collectAsStateWithLifecycle()
    val busy by vm.generating.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Benchmark") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        LazyColumn(contentPadding = PaddingValues16, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "Times how fast the loaded model reads a prompt and writes a reply on this phone. " +
                        "Run it for each model to compare them. It takes a few seconds to a minute and uses extra memory while it runs.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Button(
                    onClick = vm::runBenchmark,
                    enabled = loaded != null && !running && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (loaded == null) "Load a model first" else "Run on ${loaded?.fileName?.removeSuffix(".gguf")}") }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            if (results.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Results", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = vm::clearBenchmarks) { Text("Clear") }
                    }
                }
            }
            items(results, key = { it.takenAt }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(r.model, style = MaterialTheme.typography.titleSmall)
                        Text(
                            DateUtils.getRelativeTimeSpanString(r.takenAt).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Speed("Reads prompt", r.promptSpeed)
                            Speed("Writes reply", r.generationSpeed)
                        }
                    }
                }
            }
        }
    }
}

private val PaddingValues16 = androidx.compose.foundation.layout.PaddingValues(16.dp)

@Composable
private fun Speed(label: String, tokensPerSecond: Double?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            tokensPerSecond?.let { "%.1f tok/s".format(it) } ?: "n/a",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
