package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharactersTest {

    @Test
    fun aPresetWithAGreetingIsACharacter() {
        assertTrue(Preset("a", "A", "p", greeting = "Hello").isCharacter)
        assertFalse(Preset("a", "A", "p").isCharacter)
        assertFalse(Preset("a", "A", "p", greeting = "  ").isCharacter)
    }

    @Test
    fun theTitleShowsTheEmojiWhenThereIsOne() {
        assertEquals("📖 Storyteller", Preset("s", "Storyteller", "p", emoji = "📖").title)
        assertEquals("Tutor", Preset("t", "Tutor", "p").title)
    }

    @Test
    fun theBuiltInCharactersAreCompleteAndTheOldPresetsAreStillPlain() {
        val characters = Presets.characters(Presets.builtIn)
        assertTrue(characters.size >= 4)
        assertTrue(characters.all { it.prompt.isNotBlank() && it.greeting.isNotBlank() && it.emoji.isNotBlank() && it.builtIn })
        assertEquals(characters.size, characters.map { it.id }.toSet().size)
        assertTrue(Presets.builtIn.first { it.id == Presets.DEFAULT_ID }.greeting.isEmpty())
        assertEquals(Presets.builtIn.size, Presets.builtIn.map { it.id }.toSet().size)
    }

    @Test
    fun yourOwnCharactersSurviveSavingAndLoading() {
        val mine = listOf(Preset("x", "Captain", "Talk like a captain.", greeting = "All hands!", emoji = "⚓"), Preset("y", "Plain", "Be plain."))
        assertEquals(mine, Presets.fromJson(Presets.toJson(mine)))
    }

    @Test
    fun presetsSavedBeforeCharactersStillLoad() {
        val loaded = Presets.fromJson("""[{"id":"old","name":"Old","prompt":"Be old."}]""")
        assertEquals(Preset("old", "Old", "Be old."), loaded.single())
        assertFalse(loaded.single().isCharacter)
    }
}
