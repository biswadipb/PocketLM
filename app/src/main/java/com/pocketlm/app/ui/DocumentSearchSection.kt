package com.pocketlm.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.ModelCatalog

/** Settings for finding passages in attached documents by meaning, with a small embedding model. */
@Composable
internal fun DocumentSearchSection(vm: PocketViewModel) {
    val context = LocalContext.current
    val chosen by vm.embeddingFile.collectAsStateWithLifecycle()
    val installed by vm.embeddingModels.collectAsStateWithLifecycle()
    val note by vm.embeddingNote.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val embeddingCatalog by vm.embeddingCatalog.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    SectionTitle("Document search", Modifier.padding(top = 24.dp))
    Text(
        "Questions about an attached document are matched to its passages by keywords. With a small embedding model they are " +
            "matched by meaning as well, so a question worded differently from the document still finds the right part. " +
            "Each document is read once by the model and the result is kept. The model also answers /v1/embeddings on the " +
            "local API server.",
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
    Column(Modifier.selectableGroup().padding(top = 4.dp)) {
        SearchChoice("Keywords only", "Nothing extra to download.", chosen.isEmpty()) { vm.setEmbeddingModel("") }
        installed.forEach { m ->
            SearchChoice(
                m.fileName.removeSuffix(".gguf"),
                Formatter.formatShortFileSize(context, m.sizeBytes),
                chosen == m.fileName,
            ) { vm.setEmbeddingModel(m.fileName) }
        }
    }
    note?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

    val missing = embeddingCatalog.filter { c -> installed.none { it.fileName == c.fileName } }
    if (missing.isNotEmpty()) {
        Text("Download a search model", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
        missing.forEach { c ->
            val progress = downloads[c.fileName]
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name)
                    Text("${c.description} ${c.sizeMb} MB.", style = MaterialTheme.typography.bodySmall, color = muted)
                    if (progress != null) {
                        val value = progress.progress
                        if (value == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        else LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
                if (progress == null) {
                    OutlinedButton(onClick = { vm.download(c) }) { Text("Download") }
                } else {
                    TextButton(onClick = { vm.cancelDownload(c.fileName) }) { Text("Cancel") }
                }
            }
        }
        Text(
            "Once downloaded, choose it in the list above. Search models are listed apart from chat models in Models.",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}

@Composable
private fun SearchChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
