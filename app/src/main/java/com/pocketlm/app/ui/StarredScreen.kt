package com.pocketlm.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.Starred

/** Every message the user starred, across all chats. Tapping one opens its chat at that message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(vm: PocketViewModel, onBack: () -> Unit, onOpened: () -> Unit, modifier: Modifier = Modifier) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val items = remember(chats) { Starred.collect(chats) }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Starred messages") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
        )
        if (items.isEmpty()) {
            Hint("Star a message with the star under it, and it will be kept here.")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.chatId + "#" + it.index }) { item ->
                    Card(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open this message in its chat") {
                            chats.firstOrNull { it.id == item.chatId }?.let {
                                vm.openChat(it, scrollToMessage = item.index)
                                onOpened()
                            }
                        },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                item.chatTitle + if (item.isUser) " · you" else " · reply",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(item.snippet, Modifier.padding(top = 4.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
