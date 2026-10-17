package com.pocketlm.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.Kiwix
import java.text.NumberFormat

/** Settings for reading Wikipedia from a file on the phone, with no connection. */
@Composable
internal fun WikipediaSection(vm: PocketViewModel) {
    val status by vm.wikiStatus.collectAsStateWithLifecycle()
    val working by vm.wikiWorking.collectAsStateWithLifecycle()
    val pending by vm.wikiPending.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val links = LocalUriHandler.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::chooseWikipediaFile) }
    LaunchedEffect(Unit) { vm.refreshWikiStatus() }

    SectionTitle("Wikipedia without a connection", Modifier.padding(top = 24.dp))
    Text(
        "Give the Wikipedia tool a Wikipedia file from Kiwix, and it reads articles from the phone: nothing is sent anywhere. " +
            "Articles are found by their title (a question like \"who was Ada Lovelace\" works, but a search by topic does not), " +
            "and the start of the article is returned. Switch the Wikipedia tool on under Model tools.",
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
    working?.let {
        Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
    }
    val downloading = pending?.let { downloads[it] }
    if (pending != null && downloading != null) {
        Text("Downloading $pending", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        val progress = downloading.progress
        if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        TextButton(onClick = vm::cancelWikipediaDownload) { Text("Cancel the download") }
        return
    }
    if (status.configured) {
        Text(status.name.orEmpty(), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge)
        if (status.problem != null) {
            Text(status.problem.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else {
            Text("${NumberFormat.getIntegerInstance().format(status.entries)} entries", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = working == null) { Text("Choose another file") }
            TextButton(onClick = vm::forgetWikipedia) { Text("Stop using it") }
        }
    } else {
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::downloadWikipedia, enabled = working == null) { Text("Download (about ${Kiwix.APPROX_MB} MB)") }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = working == null) { Text("Choose a file") }
        }
        Text(
            "The download is English Wikipedia: the most read articles, with the start of each. For other languages or the whole " +
                "of Wikipedia, get a .zim file from library.kiwix.org and choose it here. Files up to many gigabytes work, since only " +
                "the part needed is read.",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        TextButton(onClick = { links.openUri("https://library.kiwix.org") }) { Text("Open library.kiwix.org") }
    }
}
