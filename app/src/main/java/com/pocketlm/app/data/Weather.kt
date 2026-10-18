package com.pocketlm.app.data

import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** A place found by name. */
data class Place(val name: String, val region: String, val country: String, val latitude: Double, val longitude: Double) {
    val label: String get() = listOf(name, region, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
}

/** Weather from Open-Meteo (open-meteo.com): free, no key. Only the place name leaves the phone. */
object Weather {
    private const val GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"

    fun placeUrl(name: String): String =
        "$GEOCODING?name=${URLEncoder.encode(name, "UTF-8")}&count=5&language=en&format=json"

    fun forecastUrl(place: Place): String =
        "$FORECAST?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=3"

    /** "Paris, France" is looked up as "Paris" and the rest picks between the matches. */
    fun nameOf(input: String): String = input.substringBefore(',').trim()

    fun hintOf(input: String): String = input.substringAfter(',', "").trim()

    /** The best match for the place in [json], preferring one whose region or country contains [hint]. Null if none. */
    fun pickPlace(json: String, hint: String): Place? {
        val results = JSONObject(json).optJSONArray("results") ?: return null
        val places = (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            if (!o.has("latitude") || !o.has("longitude")) return@mapNotNull null
            Place(
                o.optString("name"), o.optString("admin1"), o.optString("country"),
                o.getDouble("latitude"), o.getDouble("longitude"),
            ) to o.optString("country_code")
        }
        if (places.isEmpty()) return null
        val wanted = hint.trim().lowercase()
        if (wanted.isNotEmpty()) {
            places.firstOrNull { (p, code) ->
                wanted == code.lowercase() || p.region.lowercase().contains(wanted) || p.country.lowercase().contains(wanted)
            }?.let { return it.first }
        }
        return places.first().first
    }

    /** What the weather code means (WMO codes, as Open-Meteo reports them). */
    fun describe(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51 -> "light drizzle"
        53 -> "drizzle"
        55 -> "heavy drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80 -> "light rain showers"
        81 -> "rain showers"
        82 -> "violent rain showers"
        85 -> "light snow showers"
        86 -> "heavy snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "unclear conditions"
    }

    private fun celsius(value: Double): String = "${value.roundToInt()} °C"

    private fun both(value: Double): String = "${value.roundToInt()} °C (${(value * 9 / 5 + 32).roundToInt()} °F)"

    /** The current weather and the next days, written out for the model. */
    fun summary(place: Place, json: String): String {
        val root = JSONObject(json)
        val now = root.optJSONObject("current") ?: throw IllegalArgumentException("The weather service gave no current weather.")
        val lines = ArrayList<String>()
        val rain = now.optDouble("precipitation", 0.0)
        lines += "Weather in ${place.label} now: ${describe(now.optInt("weather_code", -1))}, " +
            "${both(now.getDouble("temperature_2m"))}, feels like ${celsius(now.optDouble("apparent_temperature", now.getDouble("temperature_2m")))}, " +
            "humidity ${now.optInt("relative_humidity_2m")}%, wind ${now.optDouble("wind_speed_10m").roundToInt()} km/h" +
            if (rain > 0) ", ${Converter.formatPlain(rain)} mm of rain falling." else "."

        val daily = root.optJSONObject("daily")
        val days = daily?.optJSONArray("time")
        if (daily != null && days != null) {
            val high = daily.optJSONArray("temperature_2m_max")
            val low = daily.optJSONArray("temperature_2m_min")
            val code = daily.optJSONArray("weather_code")
            val chance = daily.optJSONArray("precipitation_probability_max")
            for (i in 0 until days.length()) {
                val date = runCatching { LocalDate.parse(days.getString(i)) }.getOrNull() ?: continue
                val name = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
                val parts = ArrayList<String>()
                code?.let { if (!it.isNull(i)) parts += describe(it.getInt(i)) }
                if (low != null && high != null && !low.isNull(i) && !high.isNull(i)) {
                    parts += "${low.getDouble(i).roundToInt()} to ${high.getDouble(i).roundToInt()} °C"
                }
                chance?.let { if (!it.isNull(i)) parts += "${it.getInt(i)}% chance of rain" }
                lines += "$name: ${parts.joinToString(", ")}."
            }
        }
        return lines.joinToString("\n")
    }
}
