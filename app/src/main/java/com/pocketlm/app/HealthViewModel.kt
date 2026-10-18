package com.pocketlm.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketlm.app.data.Flashcard
import com.pocketlm.app.data.FlashcardDecks
import com.pocketlm.app.data.FoodEntry
import com.pocketlm.app.data.HealthLog
import com.pocketlm.app.data.HealthLogs
import com.pocketlm.app.data.HealthMath
import com.pocketlm.app.data.HealthProfile
import com.pocketlm.app.data.HealthPrompts
import com.pocketlm.app.data.HealthStore
import com.pocketlm.app.data.Leitner
import com.pocketlm.app.data.MealEstimate
import com.pocketlm.app.data.QuizParser
import com.pocketlm.app.data.QuizQuestion
import com.pocketlm.app.data.Targets
import com.pocketlm.app.data.WaterEntry
import com.pocketlm.app.data.WeightEntry
import com.pocketlm.app.data.WorkoutEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The health section: the person's profile, what they have written down, and their flashcard progress. Everything is read
 * from and saved to small files in the app's private folder ([HealthStore]) and goes nowhere else. The things that need
 * the model (estimating a meal, writing questions or cards) are asked through the chat's [PocketViewModel].
 */
class HealthViewModel(app: Application) : AndroidViewModel(app) {

    private val store = HealthStore(app)

    private val _profile = MutableStateFlow<HealthProfile?>(null)
    val profile = _profile.asStateFlow()

    private val _log = MutableStateFlow(HealthLog())
    val log = _log.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Leitner.Progress>>(emptyMap())
    val progress = _progress.asStateFlow()

    private val _madeCards = MutableStateFlow<List<Flashcard>>(emptyList())

    /** Every card: the built-in decks and the ones Piko wrote. */
    val cards: StateFlow<List<Flashcard>> = _madeCards.map { FlashcardDecks.builtIn + it }.stateIn(viewModelScope, SharingStarted.Eagerly, FlashcardDecks.builtIn)

    private val _day = MutableStateFlow(LocalDate.now().toEpochDay())

    /** Today, as days since 1970. Updated by [refreshDay] when the screen is shown again. */
    val day = _day.asStateFlow()

    val targets: StateFlow<Targets?> = _profile.map { it?.let(HealthMath::targets) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val today = combine(_log, _day) { log, day -> HealthLogs.daySummary(log, day) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HealthLogs.daySummary(HealthLog(), _day.value))

    val week = combine(_log, _day) { log, day -> HealthLogs.lastDays(log, day) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val streak = combine(_log, _day) { log, day -> HealthLogs.streak(log, day) }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _loaded = MutableStateFlow(false)

    /** False until the saved profile and log have been read. */
    val loaded = _loaded.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _profile.value = store.profile()
                _log.value = HealthLogs.pruned(store.log(), LocalDate.now().toEpochDay())
                _progress.value = store.progress()
                _madeCards.value = store.madeCards()
            }
            _loaded.value = true
        }
    }

    fun refreshDay() {
        _day.value = LocalDate.now().toEpochDay()
    }

    private fun saveLog() {
        val log = _log.value
        viewModelScope.launch(Dispatchers.IO) { store.saveLog(log) }
    }

    fun saveProfile(profile: HealthProfile) {
        _profile.value = profile
        viewModelScope.launch(Dispatchers.IO) { store.saveProfile(profile) }
    }

    fun addFood(entry: FoodEntry) {
        _log.value = HealthLogs.withFood(_log.value, entry)
        saveLog()
    }

    fun removeFood(id: String) {
        _log.value = HealthLogs.withoutFood(_log.value, id)
        saveLog()
    }

    fun addWater(ml: Int) {
        _log.value = HealthLogs.withWater(_log.value, WaterEntry(HealthLogs.newId(), _day.value, ml))
        saveLog()
    }

    fun undoWater() {
        _log.value = HealthLogs.withoutLastWater(_log.value, _day.value)
        saveLog()
    }

    fun addWorkout(name: String, minutes: Int, note: String) {
        _log.value = HealthLogs.withWorkout(_log.value, WorkoutEntry(HealthLogs.newId(), _day.value, name.trim().take(60), minutes, note.trim().take(200)))
        saveLog()
    }

    fun removeWorkout(id: String) {
        _log.value = HealthLogs.withoutWorkout(_log.value, id)
        saveLog()
    }

    fun addWeight(kg: Float) {
        _log.value = HealthLogs.withWeight(_log.value, WeightEntry(_day.value, kg))
        // The profile follows the scale, so the targets stay right.
        _profile.value?.let { saveProfile(it.copy(weightKg = kg)) }
        saveLog()
    }

    fun answerCard(card: Flashcard, remembered: Boolean) {
        _progress.value = Leitner.answered(_progress.value, card, remembered, _day.value)
        val progress = _progress.value
        viewModelScope.launch(Dispatchers.IO) { store.saveProgress(progress) }
    }

    /** Erases the profile, everything written down and the flashcard progress. */
    fun deleteEverything() {
        _profile.value = null
        _log.value = HealthLog()
        _progress.value = emptyMap()
        _madeCards.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { store.deleteAll() }
    }

    // ---- things that need the model ----------------------------------------------------------

    private val _working = MutableStateFlow<String?>(null)

    /** What the model is being asked for ("Estimating the meal…"), or null. */
    val working = _working.asStateFlow()

    private var askJob: Job? = null

    /** Stops what the model is writing. */
    fun cancelAsk() {
        askJob?.cancel()
    }

    private fun <T> ask(label: String, block: suspend () -> T?, done: (T?) -> Unit) {
        if (_working.value != null) return
        _working.value = label
        askJob = viewModelScope.launch {
            val result = try {
                block()
            } catch (e: CancellationException) {
                null
            }
            _working.value = null
            done(result)
        }
    }

    /** Asks the model to guess the nutrition of [description]; [done] gets null if it could not. */
    fun estimateMeal(chat: PocketViewModel, description: String, done: (MealEstimate?) -> Unit) = ask(
        "Estimating the meal…",
        { chat.askModel(HealthPrompts.estimateMeal(description), 160, HealthPrompts.ESTIMATE_SCHEMA)?.let(MealEstimate::parse) },
        done,
    )

    /** Asks the model for [count] more questions on [topic]. */
    fun makeQuestions(chat: PocketViewModel, topic: String, count: Int, done: (List<QuizQuestion>) -> Unit) = ask(
        "Piko is writing questions…",
        { chat.askModel(HealthPrompts.quiz(topic, count), 160 * count, QuizParser.SCHEMA)?.let { QuizParser.parse(it, topic.take(30)) } },
        { done(it.orEmpty()) },
    )

    /** Asks the model for [count] flashcards on [topic] and keeps them. */
    fun makeCards(chat: PocketViewModel, topic: String, count: Int, done: (List<Flashcard>) -> Unit) = ask(
        "Piko is writing cards…",
        { chat.askModel(HealthPrompts.flashcards(topic, count), 90 * count, FlashcardDecks.SCHEMA)?.let(FlashcardDecks::parseMade) },
        { made ->
            val cards = made.orEmpty().filter { c -> _madeCards.value.none { it.front.equals(c.front, ignoreCase = true) } }
            if (cards.isNotEmpty()) {
                _madeCards.value = (_madeCards.value + cards).takeLast(HealthStore.MAX_MADE_CARDS)
                val all = _madeCards.value
                viewModelScope.launch(Dispatchers.IO) { store.saveMadeCards(all) }
            }
            done(cards)
        },
    )
}
