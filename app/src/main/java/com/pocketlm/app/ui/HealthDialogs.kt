package com.pocketlm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketlm.app.data.Activity
import com.pocketlm.app.data.Caution
import com.pocketlm.app.data.DietKind
import com.pocketlm.app.data.Equipment
import com.pocketlm.app.data.Experience
import com.pocketlm.app.data.FoodEntry
import com.pocketlm.app.data.FoodTable
import com.pocketlm.app.data.Goal
import com.pocketlm.app.data.HealthLogs
import com.pocketlm.app.data.HealthProfile
import com.pocketlm.app.data.HealthSafety
import com.pocketlm.app.data.MealEstimate
import com.pocketlm.app.data.Sex
import com.pocketlm.app.data.Caution as HealthCaution

/** One question with a row of choices to pick from. The chosen one is announced as selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onPick(option) }, label = { Text(label(option)) })
            }
        }
    }
}

/** The text in [text] as a whole number, or null when it is not one. */
private fun String.wholeNumber(): Int? = trim().toIntOrNull()

/** The text as a decimal number (a comma is accepted as the point), or null. */
private fun String.decimal(): Float? = trim().replace(',', '.').toFloatOrNull()

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileDialog(initial: HealthProfile?, intro: String?, onSave: (HealthProfile) -> Unit, onDismiss: () -> Unit) {
    val start = initial ?: HealthProfile()
    var age by rememberSaveable { mutableStateOf(start.age.toString()) }
    var sex by rememberSaveable { mutableStateOf(start.sex) }
    var height by rememberSaveable { mutableStateOf(start.heightCm.toString()) }
    var weight by rememberSaveable { mutableStateOf(start.weightKg.let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) }) }
    var activity by rememberSaveable { mutableStateOf(start.activity) }
    var goal by rememberSaveable { mutableStateOf(start.goal) }
    var diet by rememberSaveable { mutableStateOf(start.diet) }
    var avoid by rememberSaveable { mutableStateOf(start.avoid) }
    var equipment by rememberSaveable { mutableStateOf(start.equipment) }
    var experience by rememberSaveable { mutableStateOf(start.experience) }
    var days by rememberSaveable { mutableStateOf(start.daysPerWeek.toString()) }
    var cautions by rememberSaveable { mutableStateOf(start.cautions.map { it.name }) }
    var notes by rememberSaveable { mutableStateOf(start.notes) }

    val built = HealthProfile(
        age = age.wholeNumber() ?: 0,
        sex = sex,
        heightCm = height.wholeNumber() ?: 0,
        weightKg = weight.decimal() ?: 0f,
        activity = activity,
        goal = goal,
        diet = diet,
        avoid = avoid.trim(),
        equipment = equipment,
        experience = experience,
        daysPerWeek = days.wholeNumber() ?: 0,
        cautions = Caution.entries.filter { it.name in cautions }.toSet(),
        notes = notes.trim(),
    )
    val problems = HealthSafety.problems(built)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About you") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    (intro?.plus(" ") ?: "") + "This stays on your phone. It is used only to fit plans and estimates to you, and you can delete it at any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(age, { age = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("Age") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(height, { height = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("Height (cm)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(weight, { weight = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) }, Modifier.weight(1f), label = { Text("Weight (kg)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Choice("Sex (used in the calorie equation)", Sex.entries, sex, { it.label }) { sex = it }
                Choice("How active are you?", Activity.entries, activity, { it.label }) { activity = it }
                Text(activity.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Choice("Your goal", Goal.entries, goal, { it.label }) { goal = it }
                Choice("How you eat", DietKind.entries, diet, { it.label }) { diet = it }
                OutlinedTextField(avoid, { avoid = it.take(300) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Allergies or foods to leave out") }, minLines = 1, maxLines = 3)
                Choice("Where you train", Equipment.entries, equipment, { it.label }) { equipment = it }
                Choice("Your experience", Experience.entries, experience, { it.label }) { experience = it }
                OutlinedTextField(days, { days = it.filter(Char::isDigit).take(1) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Training days a week") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("Take extra care if any of these fit", Modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge)
                Caution.entries.forEach { c ->
                    SwitchRow(
                        title = c.label,
                        description = null,
                        checked = c.name in cautions,
                        onCheckedChange = { on -> cautions = if (on) cautions + c.name else cautions - c.name },
                    )
                }
                OutlinedTextField(notes, { notes = it.take(600) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Anything else Piko should know") }, minLines = 2, maxLines = 5)
                HealthSafety.cautions(built).takeIf { problems.isEmpty() }?.forEach {
                    Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                problems.forEach {
                    Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(built) }, enabled = problems.isEmpty()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Adds something that was eaten. It can be found in the built-in table (instant, no model), or described and estimated by
 * the model, or typed in by hand. Whatever the source, the person sees and can change the numbers before they are saved.
 */
@Composable
internal fun MealDialog(
    modelReady: Boolean,
    working: String?,
    onEstimate: (String, (MealEstimate?) -> Unit) -> Unit,
    onAdd: (FoodEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var kcal by rememberSaveable { mutableStateOf("") }
    var protein by rememberSaveable { mutableStateOf("") }
    var carbs by rememberSaveable { mutableStateOf("") }
    var fat by rememberSaveable { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val matches = remember(query) { FoodTable.search(query) }

    fun fill(n: String, k: Int, p: Float, c: Float, f: Float) {
        name = n
        kcal = k.toString()
        protein = "%.1f".format(p).removeSuffix(".0")
        carbs = "%.1f".format(c).removeSuffix(".0")
        fat = "%.1f".format(f).removeSuffix(".0")
    }

    val entry = kcal.wholeNumber()?.takeIf { it in 0..5000 && name.isNotBlank() }?.let {
        FoodEntry(HealthLogs.newId(), 0L, name.trim().take(80), it, protein.decimal() ?: 0f, carbs.decimal() ?: 0f, fat.decimal() ?: 0f)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a meal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    query, { query = it.take(120); failed = false }, Modifier.fillMaxWidth(),
                    label = { Text("What did you eat? (rice, 2 eggs, a banana…)") }, singleLine = true,
                )
                matches.forEach { food ->
                    OutlinedButton(
                        onClick = { food.portion(food.servingG, 0L).let { fill("${food.name} (${food.serving})", it.kcal, it.proteinG, it.carbsG, it.fatG) } },
                        Modifier.fillMaxWidth(),
                    ) { Text("${food.name}, ${food.serving}: ${food.portion(food.servingG, 0L).kcal} kcal", maxLines = 1) }
                }
                if (query.length >= 3) {
                    OutlinedButton(
                        onClick = {
                            failed = false
                            onEstimate(query) { guess ->
                                if (guess == null) failed = true else fill(guess.name, guess.kcal, guess.proteinG, guess.carbsG, guess.fatG)
                            }
                        },
                        Modifier.fillMaxWidth(),
                        enabled = modelReady && working == null,
                    ) { Text(if (modelReady) "Ask Piko to estimate it" else "Load a model to ask Piko to estimate") }
                }
                if (working != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.padding(2.dp))
                        Text(working, Modifier.padding(top = 10.dp))
                    }
                }
                if (failed) Text("Piko could not make an estimate. Pick a food above or type the numbers in.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Check the numbers. Anything Piko estimates is a guess.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(kcal, { kcal = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Text("kcal") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(protein, { protein = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) }, Modifier.weight(1f), label = { Text("Protein g") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(carbs, { carbs = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) }, Modifier.weight(1f), label = { Text("Carbs g") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(fat, { fat = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) }, Modifier.weight(1f), label = { Text("Fat g") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            }
        },
        confirmButton = { TextButton(onClick = { entry?.let(onAdd) }, enabled = entry != null && working == null) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun WorkoutDialog(onAdd: (String, Int, String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val length = minutes.wholeNumber()?.takeIf { it in 1..600 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a workout") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("What did you do? (legs, run, yoga…)") }, singleLine = true)
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(), label = { Text("Minutes") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(note, { note = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Note (weights, how it felt)") }, maxLines = 3)
            }
        },
        confirmButton = { TextButton(onClick = { length?.let { onAdd(name, it, note) } }, enabled = length != null && name.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun WeightDialog(initial: Float?, onSave: (Float) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial?.let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) }.orEmpty()) }
    val kg = text.decimal()?.takeIf { it in 30f..300f }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weigh in") },
        text = {
            Column {
                OutlinedTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) }, Modifier.fillMaxWidth(),
                    label = { Text("Weight today (kg)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = text.isNotBlank() && kg == null, supportingText = { if (text.isNotBlank() && kg == null) Text("Enter a weight between 30 and 300 kg.") })
                Text(
                    "Weight moves up and down by a kilo or two from day to day. Look at the trend over weeks, not one morning.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { kg?.let(onSave) }, enabled = kg != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Choosing the length of a workout or meal plan before Piko writes it as a chat. */
@Composable
internal fun PlanDialog(title: String, meals: Boolean, profile: HealthProfile?, onStart: (days: Int, minutes: Int, extra: String) -> Unit, onDismiss: () -> Unit) {
    var days by rememberSaveable { mutableStateOf(if (meals) "7" else (profile?.daysPerWeek ?: 3).toString()) }
    var minutes by rememberSaveable { mutableStateOf("45") }
    var extra by rememberSaveable { mutableStateOf("") }
    val d = days.wholeNumber()?.takeIf { it in 1..7 }
    val m = minutes.wholeNumber()?.takeIf { it in 10..180 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(days, { days = it.filter(Char::isDigit).take(1) }, Modifier.fillMaxWidth(),
                    label = { Text(if (meals) "Days to plan (1 to 7)" else "Training days a week (1 to 7)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = d == null)
                if (!meals) {
                    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(),
                        label = { Text("Minutes per session (10 to 180)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = m == null)
                }
                OutlinedTextField(extra, { extra = it.take(200) }, Modifier.fillMaxWidth(),
                    label = { Text(if (meals) "Anything else (cheap, quick, high protein)" else "Focus (legs, upper body, fat loss)") }, maxLines = 3)
                Text(
                    "Piko writes the plan as a chat, so you can ask for changes. It is general guidance, not a prescription.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (d != null && m != null) onStart(d, m, extra) }, enabled = d != null && (meals || m != null)) { Text("Write it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun CoachDialog(onStart: (String) -> Unit, onDismiss: () -> Unit) {
    var topic by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Get coached") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(topic, { topic = it.take(200) }, Modifier.fillMaxWidth(),
                    label = { Text("What do you want help with? (optional)") }, maxLines = 3)
                Text(
                    "Piko asks one short question at a time and helps you build a routine you can keep. It gives general tips, not medical advice.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onStart(topic) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
