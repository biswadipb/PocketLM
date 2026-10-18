package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthInputTest {

    @Test
    fun wholeNumbersAreReadAndNonsenseIsNot() {
        assertEquals(172, HealthInput.wholeNumber("172"))
        assertEquals(30, HealthInput.wholeNumber(" 30 "))
        assertNull(HealthInput.wholeNumber(""))
        assertNull(HealthInput.wholeNumber("1.5"))
        assertNull(HealthInput.wholeNumber("abc"))
    }

    @Test
    fun aCommaWorksAsTheDecimalPoint() {
        assertEquals(72.5f, HealthInput.decimal("72.5")!!, 0f)
        assertEquals(72.5f, HealthInput.decimal("72,5")!!, 0f)
        assertEquals(80f, HealthInput.decimal("80")!!, 0f)
        assertNull(HealthInput.decimal(""))
        assertNull(HealthInput.decimal("1.2.3"))
        assertNull(HealthInput.decimal("NaN"))
        assertNull(HealthInput.decimal("Infinity"))
    }
}
