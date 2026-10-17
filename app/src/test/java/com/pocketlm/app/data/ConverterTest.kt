package com.pocketlm.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ConverterTest {

    private fun run(input: String): Converter.Result = Converter.resolve(Converter.parse(input))

    private fun units(input: String): String = (run(input) as Converter.Result.Units).text

    private fun failure(input: String): String = try {
        run(input)
        fail("expected an error for: $input")
        ""
    } catch (e: IllegalArgumentException) {
        e.message!!
    }

    @Before
    @After
    fun clean() = Currency.forget()

    @Test
    fun readsTheRequest() {
        assertEquals(ConversionRequest(10.0, "km", "miles"), Converter.parse("10 km to miles"))
        assertEquals(ConversionRequest(1000.0, "m", "km"), Converter.parse("1,000 m to km"))
        assertEquals(ConversionRequest(2.5, "kg", "lb"), Converter.parse("convert 2.5kg into lb"))
        assertEquals(ConversionRequest(-40.0, "c", "f"), Converter.parse("-40 c to f"))
        assertEquals(ConversionRequest(20.0, "$", "eur"), Converter.parse("$20 to eur"))
        assertEquals(ConversionRequest(5.0, "in", "cm"), Converter.parse("5 in to cm"))
        assertEquals(ConversionRequest(10.0, "sq in", "cm2"), Converter.parse("10 sq in to cm2"))
        assertEquals(ConversionRequest(10.0, "km", "miles"), Converter.parse("10 km in miles?"))
    }

    @Test
    fun rejectsWhatItCannotRead() {
        for (bad in listOf("", "km to miles", "ten km to miles", "10 km")) {
            try {
                Converter.parse(bad)
                fail("expected an error for: $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("for example"))
            }
        }
    }

    @Test
    fun convertsLengthMassAndVolume() {
        assertEquals("10 km = 6.21371 mi", units("10 km to miles"))
        assertEquals("1 mi = 5280 ft", units("1 mile to feet"))
        assertEquals("100 lb = 45.3592 kg", units("100 pounds to kg"))
        assertEquals("1 gal = 3.78541 l", units("1 gallon to litres"))
        assertEquals("2 cup = 473.176 ml", units("2 cups to ml"))
        assertEquals("1 st = 6.35029 kg", units("1 stone to kg"))
    }

    @Test
    fun convertsTemperatures() {
        assertEquals("72 °F = 22.2222 °C", units("72 f to c"))
        assertEquals("-40 °C = -40 °F", units("-40 c to f"))
        assertEquals("0 °C = 273.15 K", units("0 celsius to kelvin"))
        assertEquals("100 °C = 212 °F", units("100 degrees celsius to fahrenheit"))
        assertTrue(failure("-300 c to f").contains("absolute zero"))
    }

    @Test
    fun convertsSpeedAreaDataAndTime() {
        assertEquals("100 km/h = 62.1371 mph", units("100 kmh to mph"))
        assertEquals("1 ha = 2.47105 acre", units("1 hectare to acres"))
        assertEquals("1 GB = 1000 MB", units("1 gb to mb"))
        assertEquals("1 GiB = 1024 MiB", units("1 gib to mib"))
        assertEquals("1 wk = 7 d", units("1 week to days"))
        assertEquals("2 h = 120 min", units("2 hours to minutes"))
        assertEquals("1 m² = 10.7639 ft²", units("1 sqm to sqft"))
        assertEquals("10 in² = 64.516 cm²", units("10 sq in to cm2"))
    }

    @Test
    fun explainsAMismatchOrAnUnknownUnit() {
        assertTrue(failure("5 kg to km").contains("cannot be converted"))
        assertTrue(failure("5 kg to widgets").contains("don't know the unit"))
    }

    @Test
    fun wordsLikePoundsMeanMoneyOnlyWhenBothSidesAre() {
        assertEquals(Converter.Result.Money("GBP", "USD", 10.0), run("10 pounds to dollars"))
        assertEquals(Converter.Result.Money("USD", "EUR", 100.0), run("100 usd to eur"))
        assertEquals(Converter.Result.Money("INR", "USD", 5000.0), run("5,000 rupees to usd"))
        assertTrue(run("10 pounds to ounces") is Converter.Result.Units)
    }

    @Test
    fun aSignInFrontMeansMoney() {
        assertEquals(Converter.Result.Money("USD", "EUR", 20.0), run("$20 to eur"))
        assertEquals(Converter.Result.Money("EUR", "USD", 15.5), run("€15.5 in usd"))
    }

    @Test
    fun formatsNumbersForReading() {
        assertEquals("6.21371", Converter.formatPlain(6.2137119))
        assertEquals("1000", Converter.formatPlain(1000.0))
        assertEquals("0.000123457", Converter.formatPlain(0.000123456789))
        assertEquals("0", Converter.formatPlain(0.0))
        assertEquals("88.87", Converter.formatMoney(88.8654))
        assertEquals("0.006712", Converter.formatMoney(0.0067123))
    }

    private val usdRates = """{"amount":1.0,"base":"USD","date":"2026-10-16","rates":{"EUR":0.88865,"GBP":0.74989,"INR":94.96}}"""

    @Test
    fun readsRatesAndConvertsMoney() {
        val rates = Currency.parseRates(usdRates)
        assertEquals("2026-10-16", rates.date)
        assertEquals(94.96, rates.perUnit.getValue("INR"), 1e-9)

        var urls = emptyList<String>()
        val (value, date) = Currency.convert("USD", "INR", 100.0, 0L) { urls = urls + it; usdRates }
        assertEquals(9496.0, value, 1e-6)
        assertEquals("2026-10-16", date)
        assertEquals(listOf("https://api.frankfurter.dev/v1/latest?base=USD"), urls)
    }

    @Test
    fun ratesAreKeptForTwelveHours() {
        var calls = 0
        val http = { _: String -> calls++; usdRates }
        Currency.convert("USD", "EUR", 1.0, 1_000L, http)
        Currency.convert("USD", "GBP", 1.0, 1_000L + 60_000, http)
        assertEquals(1, calls)
        Currency.convert("USD", "EUR", 1.0, 1_000L + 12 * 3600_000L + 1, http)
        assertEquals(2, calls)
    }

    @Test
    fun moneyInTheSameCurrencyNeedsNoRates() {
        assertEquals(5.0 to "", Currency.convert("EUR", "EUR", 5.0, 0L) { throw IOException("no network") })
    }

    @Test
    fun unknownCurrenciesAreExplained() {
        try {
            Currency.convert("USD", "XYZ", 1.0, 0L) { usdRates }
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("XYZ"))
        }
        try {
            Currency.convert("FOO", "USD", 1.0, 0L) { throw IOException("Not found") }
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("FOO"))
        }
    }
}
