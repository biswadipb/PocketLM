package com.pocketlm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.CatalogSkill
import com.pocketlm.app.data.Skill
import com.pocketlm.app.data.SkillCatalog
import com.pocketlm.app.data.Skills
import com.pocketlm.app.data.ToolId
import java.util.UUID

private val tabs = listOf("Discover", "Connectors", "Skills")

/** A skill open in the editor. [isNew] is true until it is saved, including one opened from Discover. */
private class SkillEdit(val skill: Skill, val isNew: Boolean)

/**
 * Where the person adds to what Piko can do. Discover has ready-made skills to add, Connectors has the tools the model can
 * use and the sources it can read, and Skills lists (and lets the person write) their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeScreen(vm: PocketViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val skills by vm.skills.collectAsStateWithLifecycle()
    val enabledTools by vm.enabledTools.collectAsStateWithLifecycle()
    val notesStatus by vm.notesStatus.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<SkillEdit?>(null) }
    var deleting by remember { mutableStateOf<Skill?>(null) }

    editing?.let { edit ->
        BackHandler { editing = null }
        SkillEditor(
            edit,
            onSave = { skill -> vm.saveSkill(skill).also { if (it == null) editing = null } },
            onBack = { editing = null },
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Customize") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                TextButton(onClick = { editing = SkillEdit(Skill(UUID.randomUUID().toString(), "", "", ""), isNew = true) }) { Text("Create") }
            },
        )
        Text(
            "Add skills and tools so Piko can work smarter for you.",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryTabRow(selectedTabIndex = tab, Modifier.padding(top = 8.dp)) {
            tabs.forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
        }
        when (tab) {
            0 -> Discover(
                query = query,
                onQuery = { query = it },
                skills = skills,
                onAdd = { entry -> vm.addCatalogSkill(entry) },
                onOpen = { entry ->
                    editing = SkillEdit(entry.skill.copy(id = UUID.randomUUID().toString()), isNew = true)
                },
            )
            1 -> Connectors(
                enabledTools = enabledTools,
                onToggle = vm::setToolEnabled,
                notesFolder = notesStatus.folderName,
                onOpenSettings = onOpenSettings,
            )
            else -> MySkills(
                skills = skills,
                onToggle = vm::setSkillEnabled,
                onUse = vm::useSkill,
                onEdit = { editing = SkillEdit(it, isNew = false) },
                onDelete = { deleting = it },
                onDiscover = { tab = 0 },
            )
        }
    }

    deleting?.let { skill ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${skill.name}\"?") },
            text = { Text("Piko will not be able to use it any more. You can add it again from Discover or write it again.") },
            confirmButton = { TextButton(onClick = { vm.deleteSkill(skill.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Discover(
    query: String,
    onQuery: (String) -> Unit,
    skills: List<Skill>,
    onAdd: (CatalogSkill) -> String?,
    onOpen: (CatalogSkill) -> Unit,
) {
    var problem by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        var anything = false
        for (category in SkillCatalog.categories) {
            val entries = SkillCatalog.search(query, category)
            if (entries.isEmpty()) continue
            anything = true
            item { Text(category, Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium) }
            items(entries, key = { it.id }) { entry ->
                val added = SkillCatalog.isAdded(entry.id, skills)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(entry) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.titleSmall)
                        Text(entry.skill.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (added) {
                        Icon(Icons.Filled.Check, contentDescription = "Added", tint = MaterialTheme.colorScheme.primary)
                    } else {
                        IconButton(onClick = { problem = onAdd(entry) }) { Icon(Icons.Filled.Add, contentDescription = "Add ${entry.title}") }
                    }
                }
            }
        }
        if (!anything) item { Text("Nothing matches \"$query\".", Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Text(
                "A skill is a set of instructions for one kind of job. Piko sees only its name and description, and loads the " +
                    "rest when a request fits, so skills cost almost nothing until they are used. Small models do not always pick " +
                    "the right skill by themselves; you can also tap Use on the Skills tab.",
                Modifier.padding(vertical = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Connectors(
    enabledTools: Set<ToolId>,
    onToggle: (ToolId, Boolean) -> Unit,
    notesFolder: String?,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Text("Tools", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                "Helpers the model can use while it answers. Each is off until you switch it on, and each says what it sends out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(ToolId.entries.filter { it != ToolId.Skill }, key = { it.id }) { tool ->
            SwitchRow(
                title = tool.label,
                description = tool.blurb,
                checked = tool in enabledTools,
                onCheckedChange = { onToggle(tool, it) },
            )
        }
        item {
            TextButton(onClick = onOpenSettings) { Text("More tool options in Settings") }
        }
        item { Text("Sources", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium) }
        item {
            SourceRow("My notes folder", if (notesFolder != null) "Connected: $notesFolder" else "Not connected. Choose a folder in Settings, My notes.", onOpenSettings)
        }
        item {
            SourceRow("Wikipedia on this phone", "Optional file that lets the Wikipedia tool work offline. Set up in Settings.", onOpenSettings)
        }
        item {
            SourceRow("Chat sync", "Keep chats in step between your devices, encrypted. Set up in Settings.", onOpenSettings)
        }
    }
}

@Composable
private fun SourceRow(title: String, description: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MySkills(
    skills: List<Skill>,
    onToggle: (String, Boolean) -> Unit,
    onUse: (Skill) -> Unit,
    onEdit: (Skill) -> Unit,
    onDelete: (Skill) -> Unit,
    onDiscover: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (skills.isEmpty()) {
            item {
                Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No skills yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add ready-made skills from Discover, or tap Create to write your own.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onDiscover) { Text("Browse Discover") }
                }
            }
        }
        items(skills, key = { it.id }) { skill ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(skill.name, style = MaterialTheme.typography.titleSmall)
                        Text(skill.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = skill.enabled, onCheckedChange = { onToggle(skill.id, it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onUse(skill) }, enabled = skill.enabled) { Text("Use") }
                    TextButton(onClick = { onEdit(skill) }) { Text("Edit") }
                    TextButton(onClick = { onDelete(skill) }) { Text("Delete") }
                }
            }
        }
    }
}

/** One skill as a page: name, description and instructions, like a file the person can read and change. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillEditor(edit: SkillEdit, onSave: (Skill) -> String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf(edit.skill.name) }
    var description by remember { mutableStateOf(edit.skill.description) }
    var instructions by remember { mutableStateOf(edit.skill.instructions) }
    var problem by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (edit.isNew) "New skill" else "Edit skill") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                TextButton(onClick = {
                    problem = onSave(edit.skill.copy(name = Skills.slug(name), description = description, instructions = instructions))
                }) { Text(if (edit.isNew) "Add" else "Save") }
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { typed ->
                    name = typed.lowercase().replace(' ', '-').filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }.take(Skills.MAX_NAME_CHARS)
                },
                label = { Text("Name") },
                supportingText = { Text("Lower case words joined with hyphens, such as prep-for-meetings. This is what Piko asks for.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(Skills.MAX_DESCRIPTION_CHARS) },
                label = { Text("Description") },
                supportingText = { Text("One line saying when to use it. Piko reads this to decide.") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it.take(Skills.MAX_INSTRUCTIONS_CHARS) },
                label = { Text("Instructions") },
                supportingText = { Text("${instructions.length} / ${Skills.MAX_INSTRUCTIONS_CHARS}. Say when to use it, the steps, and the shape of the answer.") },
                minLines = 10,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
