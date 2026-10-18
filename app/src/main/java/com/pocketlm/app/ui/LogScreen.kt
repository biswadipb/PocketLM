package com.pocketlm.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel

/** A report to copy into a bug report: device details, why the app last closed, a crash if any, and the recent log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(vm: PocketViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val report by vm.diagnostics.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.refreshDiagnostics() }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Diagnostics") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = vm::refreshDiagnostics) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "If something goes wrong, copy this into your bug report. It lists your device, why PocketLM last closed " +
                    "(for example Android ending it because memory ran low), any crash, and recent warnings and errors. " +
                    "Your messages are not written to the log, but model and file names can appear, so read it before you share it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(top = 12.dp)) {
                Button(
                    onClick = { report?.let { clipboard.setText(AnnotatedString(it)) } },
                    enabled = report != null,
                ) { Text("Copy") }
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "PocketLM diagnostics")
                            putExtra(Intent.EXTRA_TEXT, report.orEmpty())
                        }
                        context.startActivity(Intent.createChooser(send, "Share diagnostics"))
                    },
                    enabled = report != null,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Share") }
                TextButton(onClick = vm::clearCrashReport, modifier = Modifier.padding(start = 8.dp)) { Text("Clear crash") }
            }
            SelectionContainer {
                Text(
                    report ?: "Collecting…",
                    Modifier.padding(top = 12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}
