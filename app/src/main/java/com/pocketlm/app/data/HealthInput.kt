package com.pocketlm.app.data

/** Reading the numbers a person types into the health forms. */
object HealthInput {
    /** [text] as a whole number, or null when it is empty or not one. */
    fun wholeNumber(text: String): Int? = text.trim().toIntOrNull()

    /** [text] as a decimal number; a comma works as the decimal point, as many keyboards give it. Null when it is not one. */
    fun decimal(text: String): Float? = text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }
}
