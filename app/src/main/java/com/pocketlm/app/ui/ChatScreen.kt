package com.pocketlm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.ChatMessage
import com.pocketlm.app.PocketViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: PocketViewModel,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val loaded by vm.loadedModel.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(loaded?.fileName?.removeSuffix(".gguf") ?: "PocketLM") },
            actions = {
                if (loaded != null) {
                    IconButton(onClick = vm::newChat, enabled = !generating && !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "New chat")
                    }
                }
                IconButton(onClick = onOpenModels) {
                    Icon(Icons.Filled.Storage, contentDescription = "Models")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Loading model…", Modifier.padding(top = 12.dp))
                }
                loaded == null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No model loaded", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Download or import a model to start chatting. Everything runs on your phone.",
                        Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onOpenModels) { Text("Choose a model") }
                }
                else -> LazyColumn(
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { MessageBubble(it) }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (loaded == null) "Load a model first" else "Message") },
                enabled = loaded != null && !generating && !loading,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
            )
            if (generating) {
                IconButton(onClick = vm::stopGeneration) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(
                    onClick = { vm.send(input); input = "" },
                    enabled = loaded != null && !loading && input.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (message.isUser) scheme.primaryContainer else scheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            if (message.text.isEmpty()) {
                CircularProgressIndicator(Modifier.padding(14.dp), strokeWidth = 2.dp)
            } else {
                Text(message.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}
