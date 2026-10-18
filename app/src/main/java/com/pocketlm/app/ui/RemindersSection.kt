package com.pocketlm.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.Reminders
import java.time.Instant
import java.time.ZoneId

/**
 * The reminders the model has set and that have not gone off yet, with a way to delete them. Shown while the tool is on,
 * or while reminders are waiting, so none can be forgotten after the tool is switched off.
 */
@Composable
fun RemindersSection(vm: PocketViewModel, toolOn: Boolean) {
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val exact by vm.remindersExact.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // A reminder may have gone off, or exact timing may have been allowed, while this screen was closed.
    LaunchedEffect(Unit) { vm.refreshReminders() }
    if (!toolOn && reminders.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("Reminders waiting", style = MaterialTheme.typography.labelLarge)
        if (reminders.isEmpty()) {
            Text(
                "None. When you ask Piko to remind you of something, it shows up here until it goes off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        reminders.forEach { reminder ->
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(reminder.text)
                    Text(
                        Reminders.describe(Instant.ofEpochMilli(reminder.at), now, zone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.deleteReminder(reminder) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete reminder: ${reminder.text}")
                }
            }
        }
        if (!exact && Build.VERSION.SDK_INT >= 31) {
            Text(
                "Android is not letting PocketLM set exact alarms, so a reminder can arrive a few minutes late.",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Allow exact timing") }
        }
    }
}
