package com.pocketlm.app.data

import com.pocketlm.app.data.VoiceLoop.Action
import com.pocketlm.app.data.VoiceLoop.Event
import com.pocketlm.app.data.VoiceLoop.Phase
import com.pocketlm.app.data.VoiceLoop.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLoopTest {

    private fun step(state: State, event: Event) = VoiceLoop.next(state, event)

    @Test
    fun aConversationGoesAroundTheWholeLoop() {
        var (state, action) = step(State(), Event.Start)
        assertEquals(State(Phase.Listening), state); assertEquals(Action.Listen, action)

        step(state, Event.Heard("What is the capital of France?")).let { state = it.first; action = it.second }
        assertEquals(Phase.Thinking, state.phase)
        assertEquals(Action.Send("What is the capital of France?"), action)

        step(state, Event.ReplyReady("Paris.")).let { state = it.first; action = it.second }
        assertEquals(Phase.Speaking, state.phase)
        assertEquals(Action.Speak("Paris."), action)

        step(state, Event.SpeechFinished).let { state = it.first; action = it.second }
        assertEquals(Phase.Listening, state.phase)
        assertEquals(Action.Listen, action)

        step(state, Event.Stop).let { state = it.first; action = it.second }
        assertEquals(State(), state)
        assertEquals(Action.StopAll, action)
    }

    @Test
    fun nothingHappensUntilItIsStarted() {
        for (event in listOf(Event.Heard("hi"), Event.NoSpeech, Event.ReplyReady("x"), Event.SpeechFinished, Event.Stop)) {
            assertEquals(State() to Action.None, step(State(), event))
        }
    }

    @Test
    fun stoppingWhileOffDoesNothingButStoppingWhileBusyEndsEverything() {
        for (phase in listOf(Phase.Listening, Phase.Thinking, Phase.Speaking)) {
            assertEquals(State() to Action.StopAll, step(State(phase), Event.Stop))
        }
    }

    @Test
    fun silenceMakesItListenAgainUntilTheLimit() {
        var state = State(Phase.Listening)
        repeat(VoiceLoop.MAX_SILENCES - 1) { n ->
            val (next, action) = step(state, Event.NoSpeech)
            assertEquals(Phase.Listening, next.phase)
            assertEquals(n + 1, next.silences)
            assertEquals(Action.Listen, action)
            state = next
        }
        val (ended, action) = step(state, Event.NoSpeech)
        assertEquals(Phase.Off, ended.phase)
        assertEquals(Action.StopAll, action)
        assertTrue(ended.note!!.contains("Nobody spoke"))
    }

    @Test
    fun hearingSomethingResetsTheSilenceCount() {
        val quiet = State(Phase.Listening, silences = 2)
        val (thinking, _) = step(quiet, Event.Heard("hello"))
        assertEquals(0, thinking.silences)
        val (listening, _) = step(thinking, Event.ReplyReady(""))
        assertEquals(State(Phase.Listening), listening)
    }

    @Test
    fun anEmptyOrBlankSentenceCountsAsSilence() {
        val (state, action) = step(State(Phase.Listening), Event.Heard("   "))
        assertEquals(State(Phase.Listening, 1), state)
        assertEquals(Action.Listen, action)
    }

    @Test
    fun aSpokenStopEndsTheConversation() {
        for (phrase in listOf("stop", "Stop listening.", "goodbye", "That's all", "end conversation please", "Exit")) {
            val (state, action) = step(State(Phase.Listening), Event.Heard(phrase))
            assertEquals(phrase, Phase.Off, state.phase)
            assertEquals(Action.StopAll, action)
        }
    }

    @Test
    fun aSentenceThatMerelyContainsStopIsAnOrdinaryQuestion() {
        for (text in listOf("How do I stop a bleeding nose?", "Stop signs in France", "Tell me about the Cancel culture")) {
            assertFalse(text, VoiceLoop.isStopPhrase(text))
            assertEquals(Phase.Thinking, step(State(Phase.Listening), Event.Heard(text)).first.phase)
        }
    }

    @Test
    fun aReplyWithNothingToSayGoesBackToListening() {
        assertEquals(State(Phase.Listening) to Action.Listen, step(State(Phase.Thinking), Event.ReplyReady(null)))
        assertEquals(State(Phase.Listening) to Action.Listen, step(State(Phase.Thinking), Event.ReplyReady("  ")))
    }

    @Test
    fun eventsThatDoNotBelongToThePhaseAreIgnored() {
        assertEquals(State(Phase.Thinking) to Action.None, step(State(Phase.Thinking), Event.Heard("hello")))
        assertEquals(State(Phase.Thinking) to Action.None, step(State(Phase.Thinking), Event.SpeechFinished))
        assertEquals(State(Phase.Speaking) to Action.None, step(State(Phase.Speaking), Event.Heard("hello")))
        assertEquals(State(Phase.Speaking) to Action.None, step(State(Phase.Speaking), Event.NoSpeech))
        assertEquals(State(Phase.Listening) to Action.None, step(State(Phase.Listening), Event.SpeechFinished))
        assertEquals(State(Phase.Listening) to Action.None, step(State(Phase.Listening), Event.ReplyReady("late")))
    }

    @Test
    fun aFatalProblemEndsItWithAMessageAndAMinorOneIsJustSilence() {
        val (ended, action) = step(State(Phase.Listening), Event.Failed("The microphone could not be used.", fatal = true))
        assertEquals(Phase.Off, ended.phase)
        assertEquals("The microphone could not be used.", ended.note)
        assertEquals(Action.StopAll, action)

        val (again, listen) = step(State(Phase.Listening), Event.Failed("hiccup", fatal = false))
        assertEquals(State(Phase.Listening, 1), again)
        assertEquals(Action.Listen, listen)
    }

    @Test
    fun recognitionErrorCodesBecomeTheRightEvents() {
        assertEquals(Event.NoSpeech, VoiceLoop.failure(6))   // speech timeout
        assertEquals(Event.NoSpeech, VoiceLoop.failure(7))   // no match
        assertEquals(Event.NoSpeech, VoiceLoop.failure(8))   // recogniser busy
        assertTrue((VoiceLoop.failure(9) as Event.Failed).message.contains("permission"))
        assertTrue((VoiceLoop.failure(2) as Event.Failed).message.contains("internet"))
        assertTrue((VoiceLoop.failure(13) as Event.Failed).message.contains("language"))
        assertTrue((VoiceLoop.failure(99) as Event.Failed).fatal)
        assertNull((VoiceLoop.failure(6) as? Event.Failed))
    }
}
