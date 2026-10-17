package com.pocketlm.app.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketlm.app.data.Attachment
import com.pocketlm.app.data.Project
import com.pocketlm.app.data.Projects
import com.pocketlm.app.data.SavedChat

/**
 * Projects: groups of chats that share instructions and documents. The list comes first; choosing a project opens
 * it for editing, with its documents, its chats, and a button to start a new chat in it.
 */
@Composable
fun ProjectsDialog(
    projects: List<Project>,
    chats: List<SavedChat>,
    busyReading: String?,
    onCreate: (name: String, instructions: String) -> String?,
    onUpdate: (id: String, name: String, instructions: String) -> Unit,
    onDelete: (Project) -> Unit,
    onAddDocument: (projectId: String, Uri) -> Unit,
    onRemoveDocument: (projectId: String, Attachment) -> Unit,
    onStartChat: (Project) -> Unit,
    onOpenChat: (SavedChat) -> Unit,
    onDismiss: () -> Unit,
) {
    // null: the list; "": a new project; otherwise the id of the project being edited
    var editing by remember { mutableStateOf<String?>(null) }
    val project = projects.firstOrNull { it.id == editing }

    if (editing == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Projects") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "A project groups chats that share instructions and documents. Every chat in it follows the instructions " +
                            "and can answer from the documents without attaching them again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    projects.forEach { p ->
                        val count = chats.count { it.projectId == p.id }
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Edit this project") { editing = p.id }.heightIn(min = 48.dp).padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "$count ${if (count == 1) "chat" else "chats"} · ${p.documents.size} ${if (p.documents.size == 1) "document" else "documents"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (projects.isEmpty()) Text("No projects yet.", Modifier.padding(top = 12.dp))
                    TextButton(onClick = { editing = "" }) { Text("New project") }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )
        return
    }

    var name by remember(editing) { mutableStateOf(project?.name.orEmpty()) }
    var instructions by remember(editing) { mutableStateOf(project?.instructions.orEmpty()) }
    var confirmDelete by remember(editing) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { project?.let { p -> onAddDocument(p.id, it) } }
    }

    if (confirmDelete && project != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${project.name}\"?") },
            text = { Text("Its instructions and documents are removed. Its chats stay, without the project.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(project); editing = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (project == null) "New project" else "Project") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Projects.MAX_NAME) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it.take(Projects.MAX_INSTRUCTIONS) },
                    label = { Text("Instructions") },
                    supportingText = { Text("Added to the system prompt of every chat in this project.") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (project != null) {
                    Text("Documents", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    project.documents.forEach { doc ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(doc.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { onRemoveDocument(project.id, doc) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove ${doc.name}")
                            }
                        }
                    }
                    if (project.documents.isEmpty()) {
                        Text("None yet.", style = MaterialTheme.typography.bodySmall)
                    }
                    busyReading?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = busyReading == null && project.documents.size < Projects.MAX_DOCUMENTS,
                    ) { Text("Add a document") }

                    val inProject = chats.filter { it.projectId == project.id }
                    Text("Chats", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    inProject.take(6).forEach { chat ->
                        Text(
                            chat.title,
                            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open this chat") { onOpenChat(chat) }.heightIn(min = 48.dp).padding(vertical = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (inProject.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
                    if (inProject.size > 6) Text("and ${inProject.size - 6} more", style = MaterialTheme.typography.bodySmall)
                    Row {
                        OutlinedButton(onClick = { onUpdate(project.id, name, instructions); onStartChat(project) }) { Text("New chat here") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete project", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (project == null) editing = onCreate(name, instructions)
                    else {
                        onUpdate(project.id, name, instructions)
                        editing = null
                    }
                },
                enabled = Projects.cleanName(name) != null,
            ) { Text(if (project == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = { editing = null }) { Text("Back") } },
    )
}
