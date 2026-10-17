package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    private val places = """{"results":[
        {"id":1,"name":"Paris","latitude":48.85341,"longitude":2.3488,"country_code":"FR","country":"France","admin1":"Île-de-France"},
        {"id":2,"name":"Paris","latitude":33.66094,"longitude":-95.55551,"country_code":"US","country":"United States","admin1":"Texas"},
        {"id":3,"name":"Paris","latitude":36.30201,"longitude":-88.32671,"country_code":"US","country":"United States","admin1":"Tennessee"}
    ]}"""

    private val forecast = """{"latitude":22.6,"longitude":88.4,"current":{"time":"2026-10-17T19:15","temperature_2m":28.6,
        "apparent_temperature":34.2,"relative_humidity_2m":76,"precipitation":0.00,"weather_code":0,"wind_speed_10m":3.6},
        "daily":{"time":["2026-10-17","2026-10-18","2026-10-19"],"weather_code":[80,3,3],
        "temperature_2m_max":[32.8,32.4,32.7],"temperature_2m_min":[26.0,25.5,24.9],"precipitation_probability_max":[78,32,0]}}"""

    @Test
    fun theFirstMatchIsUsedUnlessAHintPicksAnother() {
        assertEquals("Île-de-France", Weather.pickPlace(places, "")!!.region)
        assertEquals("Texas", Weather.pickPlace(places, "texas")!!.region)
        assertEquals("Tennessee", Weather.pickPlace(places, "Tennessee")!!.region)
        assertEquals("France", Weather.pickPlace(places, "FR")!!.country)
        assertEquals("Île-de-France", Weather.pickPlace(places, "nowhere")!!.region)
    }

    @Test
    fun noMatchesGivesNull() {
        assertNull(Weather.pickPlace("""{"generationtime_ms":0.1}""", ""))
        assertNull(Weather.pickPlace("""{"results":[]}""", ""))
    }

    @Test
    fun splitsThePlaceFromTheHint() {
        assertEquals("Paris", Weather.nameOf("Paris, Texas"))
        assertEquals("Texas", Weather.hintOf("Paris, Texas"))
        assertEquals("Berlin", Weather.nameOf(" Berlin "))
        assertEquals("", Weather.hintOf("Berlin"))
    }

    @Test
    fun buildsTheRequests() {
        assertTrue(Weather.placeUrl("São Paulo").startsWith("https://geocoding-api.open-meteo.com/v1/search?name=S%C3%A3o+Paulo&count=5"))
        val url = Weather.forecastUrl(Place("Paris", "", "France", 48.85, 2.35))
        assertTrue(url.contains("latitude=48.85&longitude=2.35"))
        assertTrue(url.contains("timezone=auto"))
    }

    @Test
    fun writesTheWeatherOut() {
        val place = Place("Kolkata", "West Bengal", "India", 22.56, 88.36)
        val lines = Weather.summary(place, forecast).lines()
        assertEquals(
            "Weather in Kolkata, West Bengal, India now: clear sky, 29 °C (83 °F), feels like 34 °C, humidity 76%, wind 4 km/h.",
            lines[0],
        )
        assertEquals("Sat 17 Oct: light rain showers, 26 to 33 °C, 78% chance of rain.", lines[1])
        assertEquals("Sun 18 Oct: overcast, 26 to 32 °C, 32% chance of rain.", lines[2])
        assertEquals(4, lines.size)
    }

    @Test
    fun mentionsRainThatIsFalling() {
        val json = """{"current":{"temperature_2m":10.0,"apparent_temperature":8.0,"relative_humidity_2m":90,"precipitation":1.2,"weather_code":63,"wind_speed_10m":12.0}}"""
        val text = Weather.summary(Place("Oslo", "", "Norway", 0.0, 0.0), json)
        assertEquals("Weather in Oslo, Norway now: rain, 10 °C (50 °F), feels like 8 °C, humidity 90%, wind 12 km/h, 1.2 mm of rain falling.", text)
    }

    @Test
    fun aMissingCurrentSectionIsAnError() {
        try {
            Weather.summary(Place("X", "", "", 0.0, 0.0), "{}")
            org.junit.Assert.fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no current weather"))
        }
    }

    @Test
    fun knowsTheWeatherCodes() {
        assertEquals("clear sky", Weather.describe(0))
        assertEquals("thunderstorm with hail", Weather.describe(99))
        assertEquals("fog", Weather.describe(45))
        assertEquals("unclear conditions", Weather.describe(1234))
    }
}
