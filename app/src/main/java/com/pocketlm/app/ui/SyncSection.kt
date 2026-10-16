package com.pocketlm.app.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.FolderBackend
import com.pocketlm.app.data.SyncKind
import com.pocketlm.app.data.SyncTarget

/** The Settings section for keeping chats in step between a person's devices. */
@Composable
internal fun SyncSection(vm: PocketViewModel) {
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    var setup by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    if (setup) {
        SyncSetupDialog(vm = vm, onDismiss = { setup = false })
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Turn off sync?") },
            text = {
                Text(
                    "Your chats stay on this phone, and the encrypted files stay where they are. " +
                        "You can connect again later with the same passphrase.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; vm.disconnectSync() }) { Text("Turn off") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }

    SectionTitle("Sync between devices", Modifier.padding(top = 24.dp))
    if (!status.configured) {
        Text(
            "Keep your chats, with their photos and attached files, the same on all your devices. They go to a folder " +
                "(for example in Google Drive, Dropbox, OneDrive, Nextcloud or Syncthing), a WebDAV server or S3-compatible " +
                "storage that you choose, and are encrypted on this phone with a passphrase first, so the service only " +
                "ever sees scrambled files.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Button(onClick = { setup = true }, Modifier.padding(top = 8.dp)) { Text("Set up sync") }
        return
    }

    Text(status.description, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge)
    Text(
        if (status.lastSyncAt > 0) {
            "Last synced " + DateUtils.getRelativeTimeSpanString(status.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        } else "Not synced yet",
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
    status.lastSummary?.takeIf { status.lastError == null }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
    }
    status.lastError?.let {
        Text("Last attempt failed: $it", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = vm::syncNow, enabled = !status.running) { Text(if (status.running) "Syncing…" else "Sync now") }
        if (status.running && status.progress.isNotEmpty()) {
            Text(status.progress, style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }

    SyncSwitch(
        "Sync automatically",
        "After a chat is saved or changed, and when the app opens.",
        status.autoSync,
        vm::setAutoSync,
    )
    SyncSwitch(
        "Only on Wi-Fi or other unmetered networks",
        "Automatic syncs wait for one. Sync now always runs.",
        status.wifiOnly,
        vm::setSyncWifiOnly,
    )
    SyncSwitch(
        "Also sync settings",
        "Generation settings, system prompts, saved prompts and memory notes. Not the API key, app lock, downloaded models " +
            "or choices that depend on the phone, like threads and theme.",
        status.syncSettings,
        vm::setSyncSettings,
    )
    TextButton(onClick = { confirmDisconnect = true }) { Text("Turn off sync") }
}

@Composable
private fun SyncSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SyncSetupDialog(vm: PocketViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val status by vm.syncStatus.collectAsStateWithLifecycle()

    var kind by remember { mutableStateOf(SyncKind.Folder) }
    var folder by remember { mutableStateOf<Uri?>(null) }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("us-east-1") }
    var bucket by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var accessKey by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { FolderBackend.persistPermission(context, uri) }
            folder = uri
        }
    }

    // Close once the connection has been made.
    LaunchedEffect(status.configured, status.running) {
        if (started && status.configured && !status.running) onDismiss()
    }

    val target = SyncTarget(
        kind = kind,
        folderUri = folder?.toString().orEmpty(),
        url = url, username = username, password = password,
        endpoint = endpoint, region = region, bucket = bucket, prefix = prefix, accessKey = accessKey, secretKey = secretKey,
    )

    AlertDialog(
        onDismissRequest = { if (!status.running) onDismiss() },
        title = { Text("Set up sync") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Where should your chats be kept?", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    SyncKind.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = option == kind, onClick = { kind = option }, role = Role.RadioButton).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == kind, onClick = null)
                            Text(option.label, Modifier.padding(start = 12.dp))
                        }
                    }
                }

                when (kind) {
                    SyncKind.Folder -> {
                        Text(
                            "Pick a folder in any storage app on this phone, such as one inside Google Drive, Dropbox, OneDrive, " +
                                "Nextcloud or Syncthing. That app carries the files to your other devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { picker.launch(null) }) { Text(if (folder == null) "Choose folder" else "Choose another folder") }
                        folder?.let { Text("Folder chosen", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                    }
                    SyncKind.WebDav -> {
                        Field("Folder address", url, { url = it }, "https://cloud.example.com/remote.php/dav/files/you/PocketLM", KeyboardType.Uri)
                        Field("User name", username, { username = it })
                        Field("Password or app password", password, { password = it }, secret = true)
                        Text(
                            "Create the folder on your server first. Use an app password if the server offers them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SyncKind.S3 -> {
                        Field("Endpoint", endpoint, { endpoint = it }, "https://s3.us-east-1.amazonaws.com", KeyboardType.Uri)
                        Field("Region", region, { region = it })
                        Field("Bucket", bucket, { bucket = it })
                        Field("Folder in the bucket (optional)", prefix, { prefix = it }, "pocketlm")
                        Field("Access key", accessKey, { accessKey = it })
                        Field("Secret key", secretKey, { secretKey = it }, secret = true)
                        Text(
                            "Works with Amazon S3, Cloudflare R2, Backblaze B2, Wasabi, MinIO and similar. " +
                                "The key needs permission to list, read, write and delete in the bucket.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text("Passphrase", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Field("Passphrase", passphrase, { passphrase = it }, secret = true)
                Field("Repeat passphrase (first device only)", repeat, { repeat = it }, secret = true)
                Text(
                    "Your chats are encrypted on this phone with this passphrase before they are uploaded, so nobody who can see " +
                        "the files can read them. On your other devices, enter the same passphrase. It is not stored, and it " +
                        "cannot be recovered if you forget it. At least 8 characters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status.connectError?.takeIf { !status.running }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (status.running) {
                    Text(status.progress.ifEmpty { "Working…" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !status.running,
                onClick = { started = true; vm.connectSync(target, passphrase, repeat) },
            ) { Text("Connect") }
        },
        dismissButton = { TextButton(enabled = !status.running, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
