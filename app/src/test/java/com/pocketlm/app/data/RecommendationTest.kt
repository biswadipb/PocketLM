package com.pocketlm.app.data

import com.pocketlm.app.data.MemoryEstimate.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecommendationTest {

    private fun model(name: String, mb: Int, projector: String? = null) =
        CatalogModel(name, "", "$name.gguf", "https://x/$name", mb, projector, projector?.let { "https://x/$it" })

    private val small = model("small", 400)
    private val medium = model("medium", 1100)
    private val large = model("large", 2000)
    private val vision = model("vision", 500, projector = "mmproj.gguf")

    @Test
    fun picksTheLargestModelThatFitsComfortably() {
        val levels = mapOf(small to Level.Comfortable, medium to Level.Comfortable, large to Level.Tight, vision to Level.Comfortable)
        assertEquals(medium, Recommendation.pick(listOf(small, medium, large, vision)) { levels.getValue(it) })
    }

    @Test
    fun aVisionModelIsNeverThePick() {
        assertEquals(small, Recommendation.pick(listOf(small, vision)) { Level.Comfortable })
        assertEquals(small, Recommendation.pick(listOf(vision, small)) { Level.Comfortable })
        assertNull(Recommendation.pick(listOf(vision)) { Level.Comfortable })
    }

    @Test
    fun nothingIsPickedWhenNothingFitsComfortably() {
        assertNull(Recommendation.pick(listOf(small, medium)) { Level.TooBig })
        assertNull(Recommendation.pick(emptyList()) { Level.Comfortable })
    }
}
