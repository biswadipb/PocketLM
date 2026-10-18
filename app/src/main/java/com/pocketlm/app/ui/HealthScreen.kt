package com.pocketlm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketlm.app.HealthViewModel
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.HealthMath
import com.pocketlm.app.data.HealthPrompts
import com.pocketlm.app.data.HealthSafety
import com.pocketlm.app.data.HealthProfile
import com.pocketlm.app.data.Targets
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private enum class HealthView { Hub, Quiz, Cards }

private enum class Sheet { None, Profile, Meal, Workout, Weight, WorkoutPlan, MealPlan, Coach, Delete }

/** The tag every chat started from this section carries, so they can be found together in History. */
internal const val HEALTH_TAG = "health"

/**
 * Fitness, gym, food and diet in one place: a row of things Piko can do (plans, a meal log, quizzes, flashcards, a coach),
 * today's numbers, and the person's profile. What is written down stays on the phone. Piko gives general information and
 * is not a doctor or a dietitian, which the screen says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(
    chat: PocketViewModel,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
    health: HealthViewModel = viewModel(),
) {
    var view by rememberSaveable { mutableStateOf(HealthView.Hub) }
    BackHandler(enabled = view != HealthView.Hub) { view = HealthView.Hub }
    LifecycleResumeEffect(Unit) {
        health.refreshDay()
        onPauseOrDispose { }
    }

    when (view) {
        HealthView.Quiz -> {
            HealthQuizScreen(chat, health, onBack = { view = HealthView.Hub }, modifier = modifier)
            return
        }
        HealthView.Cards -> {
            HealthCardsScreen(chat, health, onBack = { view = HealthView.Hub }, modifier = modifier)
            return
        }
        HealthView.Hub -> Unit
    }

    val profile by health.profile.collectAsStateWithLifecycle()
    val targets by health.targets.collectAsStateWithLifecycle()
    val today by health.today.collectAsStateWithLifecycle()
    val week by health.week.collectAsStateWithLifecycle()
    val streak by health.streak.collectAsStateWithLifecycle()
    val log by health.log.collectAsStateWithLifecycle()
    val chats by chat.chats.collectAsStateWithLifecycle()
    val loaded by chat.loadedModel.collectAsStateWithLifecycle()
    val working by health.working.collectAsStateWithLifecycle()
    val day by health.day.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(Sheet.None) }
    var needProfile by remember { mutableStateOf(false) }

    /** Plans and coaching need the profile; the person is asked for it first. */
    fun needsProfile(then: Sheet) {
        if (profile == null) {
            needProfile = true
            sheet = Sheet.Profile
        } else {
            sheet = then
        }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Health and fitness") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Text(
                        "Move, eat and feel better",
                        Modifier.padding(top = 8.dp).semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Piko can plan workouts and meals, keep a simple log, quiz you and coach you. It all runs on your phone, and what you write here stays here.",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cardsOf(), key = { it.label }) { card ->
                        FeatureCard(card) {
                            when (card.action) {
                                Action.WorkoutPlan -> needsProfile(Sheet.WorkoutPlan)
                                Action.MealPlan -> needsProfile(Sheet.MealPlan)
                                Action.LogMeal -> sheet = Sheet.Meal
                                Action.LogWorkout -> sheet = Sheet.Workout
                                Action.Quiz -> view = HealthView.Quiz
                                Action.Cards -> view = HealthView.Cards
                                Action.Coach -> sheet = Sheet.Coach
                            }
                        }
                    }
                }
            }
            item { SectionHeading("Today") }
            item {
                TodayCard(
                    summary = today,
                    targets = targets,
                    week = week,
                    day = day,
                    streak = streak,
                    onWater = { health.addWater(250) },
                    onUndoWater = health::undoWater,
                    onMeal = { sheet = Sheet.Meal },
                    onWorkout = { sheet = Sheet.Workout },
                    onWeight = { sheet = Sheet.Weight },
                    lastWeight = log.weights.lastOrNull()?.kg,
                )
            }
            item { SectionHeading("About you") }
            item { ProfileCard(profile, targets) { needProfile = false; sheet = Sheet.Profile } }
            val plans = chats.filter { c -> c.tags.any { it.equals(HEALTH_TAG, ignoreCase = true) } }.take(6)
            if (plans.isNotEmpty()) {
                item { SectionHeading("Your plans and coaching chats") }
                items(plans, key = { it.id }) { plan ->
                    Card(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open this chat") { chat.openChat(plan); onOpenChat() },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(plan.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                plan.tags.filterNot { it.equals(HEALTH_TAG, ignoreCase = true) }.joinToString(" · ").ifEmpty { "Chat" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(top = 8.dp, bottom = 24.dp)) {
                    Text(
                        "Piko is not a doctor or a dietitian. It gives general information, can be wrong, and its numbers are estimates. " +
                            "Ask a professional before big changes to your food or training, and stop any exercise that hurts. " +
                            "If you are under 18, pregnant, ill, or have had an eating disorder, take extra care and talk to a professional first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { sheet = Sheet.Delete }) { Text("Delete my health data") }
                }
            }
        }
    }

    val startChat: (String, String, List<String>) -> Unit = { message, title, tags ->
        if (chat.startHealthChat(message, title, listOf(HEALTH_TAG) + tags)) onOpenChat()
    }

    when (sheet) {
        Sheet.None -> Unit
        Sheet.Profile -> ProfileDialog(
            initial = profile,
            intro = if (needProfile) "Plans work best when they fit you. Fill this in once; you can change it any time." else null,
            onSave = { health.saveProfile(it); sheet = Sheet.None },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Meal -> MealDialog(
            modelReady = loaded != null,
            working = working,
            onEstimate = { description, done -> health.estimateMeal(chat, description, done) },
            onAdd = { entry -> health.addFood(entry.copy(day = day)); sheet = Sheet.None },
            onDismiss = { health.cancelAsk(); sheet = Sheet.None },
        )
        Sheet.Workout -> WorkoutDialog(
            onAdd = { name, minutes, note -> health.addWorkout(name, minutes, note); sheet = Sheet.None },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Weight -> WeightDialog(
            initial = log.weights.lastOrNull()?.kg ?: profile?.weightKg,
            onSave = { health.addWeight(it); sheet = Sheet.None },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.WorkoutPlan -> PlanDialog(
            title = "Build a workout plan",
            meals = false,
            profile = profile,
            onStart = { days, minutes, extra ->
                val p = profile ?: return@PlanDialog
                sheet = Sheet.None
                startChat(HealthPrompts.workoutPlan(p, days, minutes, extra), "Workout plan: $days days", listOf("workout plan"))
            },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.MealPlan -> PlanDialog(
            title = "Plan my meals",
            meals = true,
            profile = profile,
            onStart = { days, _, extra ->
                val p = profile ?: return@PlanDialog
                sheet = Sheet.None
                startChat(HealthPrompts.mealPlan(p, targets, days, extra), "Meal plan: $days days", listOf("meal plan"))
            },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Coach -> CoachDialog(
            onStart = { topic ->
                sheet = Sheet.None
                startChat(HealthPrompts.coachStart(profile, topic), if (topic.isBlank()) "Coaching with Piko" else "Coaching: ${topic.take(40)}", listOf("coaching"))
            },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Delete -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { sheet = Sheet.None },
            title = { Text("Delete my health data?") },
            text = { Text("This erases your profile, your meal, water, workout and weight log and your flashcard progress from this phone. Chats stay in History.") },
            confirmButton = { TextButton(onClick = { health.deleteEverything(); sheet = Sheet.None }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { sheet = Sheet.None }) { Text("Cancel") } },
        )
    }
}

private enum class Action { WorkoutPlan, MealPlan, LogMeal, LogWorkout, Quiz, Cards, Coach }

private class HealthTile(val action: Action, val label: String, val icon: ImageVector, val colors: List<Color>)

private fun cardsOf() = listOf(
    HealthTile(Action.WorkoutPlan, "Build a workout plan", Icons.Filled.FitnessCenter, listOf(Color(0xFF6D28D9), Color(0xFF2563EB))),
    HealthTile(Action.MealPlan, "Plan my meals for the week", Icons.Filled.Restaurant, listOf(Color(0xFF047857), Color(0xFF65A30D))),
    HealthTile(Action.LogMeal, "Log a meal", Icons.Filled.Fastfood, listOf(Color(0xFFEA580C), Color(0xFFDC2626))),
    HealthTile(Action.LogWorkout, "Log a workout", Icons.Filled.DirectionsRun, listOf(Color(0xFFCA8A04), Color(0xFFEA580C))),
    HealthTile(Action.Quiz, "Quiz yourself", Icons.Filled.Quiz, listOf(Color(0xFF0369A1), Color(0xFF4F46E5))),
    HealthTile(Action.Cards, "Make flashcards for the gym and food", Icons.Filled.Style, listOf(Color(0xFFBE185D), Color(0xFF7E22CE))),
    HealthTile(Action.Coach, "Get coached step by step", Icons.Filled.SelfImprovement, listOf(Color(0xFF0F766E), Color(0xFF0369A1))),
)

@Composable
private fun FeatureCard(card: HealthTile, onClick: () -> Unit) {
    Column(
        Modifier.width(156.dp).clip(RoundedCornerShape(28.dp)).clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier.size(156.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(card.colors)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(card.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
        }
        Text(
            card.label,
            Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val numbers: NumberFormat = NumberFormat.getIntegerInstance()

@Composable
private fun StatBar(label: String, value: String, fraction: Float?) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable
private fun TodayCard(
    summary: com.pocketlm.app.data.DaySummary,
    targets: Targets?,
    week: List<com.pocketlm.app.data.DaySummary>,
    day: Long,
    streak: Int,
    onWater: () -> Unit,
    onUndoWater: () -> Unit,
    onMeal: () -> Unit,
    onWorkout: () -> Unit,
    onWeight: () -> Unit,
    lastWeight: Float?,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBar(
                "Calories",
                numbers.format(summary.kcal) + (targets?.let { " of ${numbers.format(it.calories)} kcal" } ?: " kcal"),
                targets?.let { summary.kcal / it.calories.toFloat() },
            )
            StatBar(
                "Protein",
                "${summary.proteinG.toInt()}" + (targets?.let { " of ${it.proteinG} g" } ?: " g"),
                targets?.let { summary.proteinG / it.proteinG },
            )
            val litres = { ml: Int -> "%.2f".format(ml / 1000.0).trimEnd('0').trimEnd('.') }
            StatBar(
                "Water",
                litres(summary.waterMl) + (targets?.let { " of ${litres(it.waterMl)} L" } ?: " L"),
                targets?.let { summary.waterMl / it.waterMl.toFloat() },
            )
            if (summary.workouts > 0) Text("Workout: ${summary.workoutMinutes} minutes", style = MaterialTheme.typography.bodyMedium)
            if (targets == null) {
                Text(
                    "Set up your profile to see targets. Without one this just keeps count.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WeekBars(week, day, targets?.calories)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onWater) {
                    Icon(Icons.Filled.WaterDrop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  250 ml")
                }
                if (summary.waterMl > 0) TextButton(onClick = onUndoWater) { Text("Undo") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMeal) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text(" Meal") }
                OutlinedButton(onClick = onWorkout) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text(" Workout") }
                OutlinedButton(onClick = onWeight) { Icon(Icons.Filled.MonitorWeight, null, Modifier.size(18.dp)); Text(" Weigh in") }
            }
            Text(
                buildString {
                    if (streak > 0) append(if (streak == 1) "1 day written down" else "$streak days in a row")
                    if (lastWeight != null) {
                        if (isNotEmpty()) append(" · ")
                        append("last weight ").append("%.1f".format(lastWeight)).append(" kg")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeekBars(week: List<com.pocketlm.app.data.DaySummary>, today: Long, target: Int?) {
    if (week.isEmpty()) return
    val top = maxOf(target ?: 0, week.maxOf { it.kcal }, 1).toFloat()
    Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        week.forEach { d ->
            val name = LocalDate.ofEpochDay(d.day).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$name: ${numbers.format(d.kcal)} kilocalories" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.height(52.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth(0.6f).height((52 * d.kcal / top).dp.coerceAtLeast(if (d.any) 3.dp else 1.dp))
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (d.day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                    )
                }
                Text(name.take(1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: HealthProfile?, targets: Targets?, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (profile == null) {
                Text("Plans tailored to you", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Tell Piko your age, size, goal and the kit you have, and plans, meals and targets will fit. It is saved on this phone only.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onEdit, Modifier.padding(top = 4.dp)) { Text("Set up my profile") }
            } else {
                Text(
                    "${profile.age} years · ${profile.heightCm} cm · ${"%.1f".format(profile.weightKg).removeSuffix(".0")} kg",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${profile.goal.label} · ${profile.activity.label.lowercase()} · ${profile.diet.label.lowercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (targets != null) {
                    Text(
                        "About ${numbers.format(targets.calories)} kcal, ${targets.proteinG} g protein, ${targets.carbsG} g carbohydrate, " +
                            "${targets.fatG} g fat and ${"%.1f".format(targets.waterMl / 1000.0)} L water a day. These are estimates, not advice.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    targets.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HealthSafety.cautions(profile).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "BMI ${"%.1f".format(HealthMath.bmi(profile))}: ${HealthMath.bmiBand(HealthMath.bmi(profile)).lowercase()}. BMI cannot tell muscle from fat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onEdit, Modifier.heightIn(min = 48.dp)) { Text("Edit my profile") }
            }
        }
    }
}
