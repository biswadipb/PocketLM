package com.pocketlm.app.data

/**
 * The turn-taking of a hands-free conversation: listen, send what was said, wait for the reply, read it aloud,
 * listen again. Pure, so every step can be tested without a microphone or a model.
 */
object VoiceLoop {

    /** Nothing heard this many times in a row ends the conversation, so it does not listen to an empty room forever. */
    const val MAX_SILENCES = 3

    enum class Phase { Off, Listening, Thinking, Speaking }

    data class State(
        val phase: Phase = Phase.Off,
        val silences: Int = 0,
        /** Something to tell the user, set when the conversation ends. */
        val note: String? = null,
    )

    sealed interface Event {
        data object Start : Event
        data class Heard(val text: String) : Event
        data object NoSpeech : Event
        data class Failed(val message: String, val fatal: Boolean) : Event

        /** The model finished writing (or failed to write) a reply; [text] is what it wrote, if anything. */
        data class ReplyReady(val text: String?) : Event
        data object SpeechFinished : Event

        /** The user ended it, or the app left the screen. */
        data object Stop : Event
    }

    sealed interface Action {
        data object None : Action
        data object Listen : Action
        data class Send(val text: String) : Action
        data class Speak(val text: String) : Action

        /** Stop the microphone and any speech. */
        data object StopAll : Action
    }

    fun next(state: State, event: Event): Pair<State, Action> {
        if (event == Event.Stop) {
            return if (state.phase == Phase.Off) state to Action.None else State() to Action.StopAll
        }
        return when (state.phase) {
            Phase.Off -> if (event == Event.Start) State(Phase.Listening) to Action.Listen else state to Action.None
            Phase.Listening -> when (event) {
                is Event.Heard -> {
                    val text = event.text.trim()
                    when {
                        text.isEmpty() -> silence(state)
                        isStopPhrase(text) -> State(note = "Voice conversation ended.") to Action.StopAll
                        else -> State(Phase.Thinking) to Action.Send(text)
                    }
                }
                Event.NoSpeech -> silence(state)
                is Event.Failed -> if (event.fatal) State(note = event.message) to Action.StopAll else silence(state)
                else -> state to Action.None
            }
            Phase.Thinking -> when (event) {
                is Event.ReplyReady ->
                    if (event.text.isNullOrBlank()) State(Phase.Listening) to Action.Listen
                    else State(Phase.Speaking) to Action.Speak(event.text)
                else -> state to Action.None
            }
            Phase.Speaking -> when (event) {
                Event.SpeechFinished -> State(Phase.Listening) to Action.Listen
                else -> state to Action.None
            }
        }
    }

    private fun silence(state: State): Pair<State, Action> {
        val count = state.silences + 1
        return if (count >= MAX_SILENCES) {
            State(note = "Nobody spoke, so the voice conversation ended.") to Action.StopAll
        } else {
            State(Phase.Listening, count) to Action.Listen
        }
    }

    private val stopPhrases = setOf(
        "stop", "stop listening", "stop talking", "end conversation", "end the conversation", "end voice chat",
        "goodbye", "good bye", "bye", "bye bye", "that's all", "thats all", "that is all", "exit", "cancel",
    )

    /** True if [text] is only a way of saying "end the conversation", with or without "please". */
    fun isStopPhrase(text: String): Boolean {
        val plain = text.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim().removeSuffix(" please").trim()
        return plain in stopPhrases
    }

    /**
     * What the speech recogniser's error codes mean for the conversation (the codes are those of
     * android.speech.SpeechRecognizer).
     */
    fun failure(errorCode: Int): Event = when (errorCode) {
        6, 7, 8 -> Event.NoSpeech                    // speech timeout, no match, recogniser busy: just listen again
        9 -> Event.Failed("PocketLM needs permission to use the microphone. Allow it in the phone's settings.", fatal = true)
        2, 4 -> Event.Failed("Speech recognition needs an internet connection on this phone.", fatal = true)
        3 -> Event.Failed("The microphone could not be used.", fatal = true)
        12, 13 -> Event.Failed("Speech recognition is not available in your language on this phone.", fatal = true)
        else -> Event.Failed("Speech recognition stopped working (error $errorCode).", fatal = true)
    }
}
