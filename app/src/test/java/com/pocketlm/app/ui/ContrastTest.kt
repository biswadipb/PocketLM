package com.pocketlm.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Checks that the colours the app draws text and controls in are readable against what they sit on, using the contrast
 * ratios of WCAG 2: at least 4.5 for text, 3 for borders and icons.
 */
class ContrastTest {

    private fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** The pairs the screens use: text, then what it is drawn on. */
    private fun textPairs(c: ColorScheme) = listOf(
        "text on the page" to (c.onSurface to c.surface),
        "text on the background" to (c.onBackground to c.background),
        "secondary text on the page" to (c.onSurfaceVariant to c.surface),
        "text in a reply bubble" to (c.onSurfaceVariant to c.surfaceVariant),
        "text in your bubble" to (c.onPrimaryContainer to c.primaryContainer),
        "section titles and links" to (c.primary to c.surface),
        "titles inside a reply bubble" to (c.primary to c.surfaceVariant),
        "text on a button" to (c.onPrimary to c.primary),
        "text on a secondary button" to (c.onSecondary to c.secondary),
        "errors" to (c.error to c.surface),
        "text on an error" to (c.onError to c.error),
        "the summary card" to (c.onTertiaryContainer to c.tertiaryContainer),
        "the memory meter warning" to (c.tertiary to c.surface),
        "the selected history card" to (c.onSecondaryContainer to c.secondaryContainer),
        "text on a dialog" to (c.onSurface to c.surfaceContainerHigh),
        "secondary text on a dialog" to (c.onSurfaceVariant to c.surfaceContainerHigh),
        "links on a dialog" to (c.primary to c.surfaceContainerHigh),
    )

    private fun shapePairs(c: ColorScheme) = listOf(
        "borders and outlines" to (c.outline to c.surface),
        "icons on the page" to (c.onSurfaceVariant to c.surface),
        "a switch or highlighted border" to (c.primary to c.surface),
    )

    private fun check(name: String, scheme: ColorScheme) {
        val weak = ArrayList<String>()
        for ((what, pair) in textPairs(scheme)) {
            val r = ratio(pair.first, pair.second)
            if (r < 4.5) weak += "$name: $what is %.2f:1 (needs 4.5)".format(r)
        }
        for ((what, pair) in shapePairs(scheme)) {
            val r = ratio(pair.first, pair.second)
            if (r < 3.0) weak += "$name: $what is %.2f:1 (needs 3)".format(r)
        }
        assertTrue(weak.joinToString("\n"), weak.isEmpty())
    }

    @Test
    fun theLightThemeIsReadable() = check("light", LightColors)

    @Test
    fun theDarkThemeIsReadable() = check("dark", DarkColors)

    @Test
    fun theRatioFormulaMatchesKnownValues() {
        assertTrue(ratio(Color.Black, Color.White).let { it > 20.9 && it < 21.1 })
        assertTrue(ratio(Color.White, Color.White) == 1.0)
        // The well-known "#767676 on white" is the grey that just passes at 4.54.
        assertTrue(ratio(Color(0xFF767676), Color.White).let { it > 4.5 && it < 4.6 })
    }
}
