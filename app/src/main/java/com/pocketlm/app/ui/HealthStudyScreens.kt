package com.pocketlm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketlm.app.HealthViewModel
import com.pocketlm.app.PocketViewModel
import com.pocketlm.app.data.Flashcard
import com.pocketlm.app.data.FlashcardDecks
import com.pocketlm.app.data.Leitner
import com.pocketlm.app.data.QuizBank
import com.pocketlm.app.data.QuizQuestion
import java.util.Random

private const val QUIZ_LENGTH = 10

/**
 * A multiple-choice quiz on food, training and rest. The questions come from a built-in bank of well-established facts, so
 * it works with no model; Piko can write more on any topic when one is loaded.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HealthQuizScreen(chat: PocketViewModel, health: HealthViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val loaded by chat.loadedModel.collectAsStateWithLifecycle()
    val working by health.working.collectAsStateWithLifecycle()
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    var seed by rememberSaveable { mutableLongStateOf(System.nanoTime()) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var picked by rememberSaveable { mutableStateOf<Int?>(null) }
    var made by remember { mutableStateOf<List<QuizQuestion>>(emptyList()) }
    var ask by rememberSaveable { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }

    val questions = remember(topic, seed, made) { made.ifEmpty { QuizBank.draw(QUIZ_LENGTH, Random(seed), topic) } }

    fun restart(newTopic: String?) {
        topic = newTopic
        made = emptyList()
        seed = System.nanoTime()
        index = 0
        score = 0
        picked = null
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Quiz yourself") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (made.isEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = topic == null, onClick = { restart(null) }, label = { Text("Everything") })
                    QuizBank.topics.forEach { t -> FilterChip(selected = topic == t, onClick = { restart(t) }, label = { Text(t) }) }
                }
            }
            if (index >= questions.size) {
                Text("Done: $score of ${questions.size}", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        questions.isEmpty() -> "There are no questions yet."
                        score == questions.size -> "Full marks. Well done."
                        score * 2 >= questions.size -> "A good result. The explanations are worth another look."
                        else -> "A start. Try again, or read the explanations as you go; that is how it sticks."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = { restart(topic) }) { Text("Play again") }
            } else {
                val q = questions[index]
                LinearProgressIndicator(progress = { index / questions.size.toFloat() }, Modifier.fillMaxWidth())
                Text("Question ${index + 1} of ${questions.size} · ${q.topic}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(q.text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                q.choices.forEachIndexed { i, choice ->
                    val chosen = picked
                    val mark = when {
                        chosen == null -> ""
                        i == q.answer -> "Right: "
                        i == chosen -> "Not this one: "
                        else -> ""
                    }
                    OutlinedButton(
                        onClick = {
                            if (picked == null) {
                                picked = i
                                if (i == q.answer) score++
                            }
                        },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(mark + choice, Modifier.fillMaxWidth()) }
                }
                picked?.let { chosen ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (chosen == q.answer) "Correct." else "Not quite.", style = MaterialTheme.typography.titleSmall)
                            Text(q.explanation, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { index++; picked = null }) { Text(if (index + 1 >= questions.size) "See my result" else "Next") }
                        }
                    }
                }
            }

            Text("More questions", Modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                ask, { ask = it.take(80); failed = false }, Modifier.fillMaxWidth(),
                label = { Text("A topic (creatine, sleep, stretching…)") }, singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    failed = false
                    health.makeQuestions(chat, ask.trim(), 5) { list ->
                        if (list.isEmpty()) failed = true
                        else {
                            made = list
                            seed = System.nanoTime()
                            index = 0
                            score = 0
                            picked = null
                        }
                    }
                },
                enabled = loaded != null && working == null && ask.trim().length >= 3,
            ) { Text(if (loaded != null) "Ask Piko for 5 questions" else "Load a model to ask Piko") }
            if (working != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(2.dp))
                    Text(working ?: "", Modifier.padding(top = 10.dp))
                }
            }
            if (failed) Text("Piko could not write usable questions this time. Try again or word the topic differently.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(
                "Questions Piko writes can contain mistakes, so check anything that matters. The built-in questions are well-established basics.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Flashcards on gym and nutrition basics, shown again after a growing wait (the Leitner method): a card you remember comes
 * back later, one you miss comes back soon. Piko can add cards on any topic.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HealthCardsScreen(chat: PocketViewModel, health: HealthViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val loaded by chat.loadedModel.collectAsStateWithLifecycle()
    val working by health.working.collectAsStateWithLifecycle()
    val all by health.cards.collectAsStateWithLifecycle()
    val progress by health.progress.collectAsStateWithLifecycle()
    val day by health.day.collectAsStateWithLifecycle()
    var deck by rememberSaveable { mutableStateOf<String?>(null) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    var lastId by rememberSaveable { mutableStateOf<String?>(null) }
    var ask by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    val decks = remember(all) { (FlashcardDecks.decks + if (all.any { it.deck == FlashcardDecks.MADE }) listOf(FlashcardDecks.MADE) else emptyList()) }
    val shown: List<Flashcard> = if (deck == null) all else all.filter { it.deck == deck }
    val due = Leitner.dueCards(shown, progress, day)
    val current = due.firstOrNull { it.id != lastId } ?: due.firstOrNull()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Flashcards") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = deck == null, onClick = { deck = null; revealed = false }, label = { Text("All decks") })
                decks.forEach { d -> FilterChip(selected = deck == d, onClick = { deck = d; revealed = false }, label = { Text(d) }) }
            }
            Text(
                "${Leitner.known(shown, progress)} of ${shown.size} cards known · ${due.size} to look at today",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current == null) {
                Text("All done for today", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Text("The cards you know come back after a few days, so a little each day is enough.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(current.deck, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(current.front, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        if (revealed) {
                            Text(current.back, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (!revealed) {
                    Button(onClick = { revealed = true }, Modifier.fillMaxWidth()) { Text("Show the answer") }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { health.answerCard(current, false); lastId = current.id; revealed = false },
                            Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("I forgot") }
                        Button(
                            onClick = { health.answerCard(current, true); lastId = current.id; revealed = false },
                            Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("I knew it") }
                    }
                }
            }

            Text("More cards", Modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                ask, { ask = it.take(80); message = null }, Modifier.fillMaxWidth(),
                label = { Text("A topic (protein foods, squat form, reading labels…)") }, singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    message = null
                    health.makeCards(chat, ask.trim(), 6) { made ->
                        message = if (made.isEmpty()) "Piko could not write new cards this time. Try again or word the topic differently."
                        else "Added ${made.size} cards to \"${FlashcardDecks.MADE}\"."
                        if (made.isNotEmpty()) {
                            deck = FlashcardDecks.MADE
                            revealed = false
                        }
                    }
                },
                enabled = loaded != null && working == null && ask.trim().length >= 3,
            ) { Text(if (loaded != null) "Ask Piko for 6 cards" else "Load a model to ask Piko") }
            if (working != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(2.dp))
                    Text(working ?: "", Modifier.padding(top = 10.dp))
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "Cards Piko writes can contain mistakes, so check anything that matters.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
