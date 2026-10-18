package com.pocketlm.app.data

import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/** One unit the converter knows. [factor] is how many of the category's base unit make one of these. */
private class UnitDef(val category: String, val symbol: String, val factor: Double, vararg val names: String)

/** What was asked for, read from words such as "10 km to miles" or "100 usd in eur". */
data class ConversionRequest(val amount: Double, val from: String, val to: String)

object Converter {

    private val units: List<UnitDef> = buildList {
        // Length, base metre
        add(UnitDef("length", "mm", 0.001, "mm", "millimeter", "millimeters", "millimetre", "millimetres"))
        add(UnitDef("length", "cm", 0.01, "cm", "centimeter", "centimeters", "centimetre", "centimetres"))
        add(UnitDef("length", "m", 1.0, "m", "meter", "meters", "metre", "metres"))
        add(UnitDef("length", "km", 1000.0, "km", "kilometer", "kilometers", "kilometre", "kilometres", "kms"))
        add(UnitDef("length", "in", 0.0254, "in", "inch", "inches", "\""))
        add(UnitDef("length", "ft", 0.3048, "ft", "foot", "feet", "'"))
        add(UnitDef("length", "yd", 0.9144, "yd", "yard", "yards", "yds"))
        add(UnitDef("length", "mi", 1609.344, "mi", "mile", "miles"))
        add(UnitDef("length", "nmi", 1852.0, "nmi", "nautical mile", "nautical miles"))
        // Mass, base gram
        add(UnitDef("mass", "mg", 0.001, "mg", "milligram", "milligrams"))
        add(UnitDef("mass", "g", 1.0, "g", "gram", "grams", "gm"))
        add(UnitDef("mass", "kg", 1000.0, "kg", "kgs", "kilogram", "kilograms", "kilo", "kilos"))
        add(UnitDef("mass", "t", 1_000_000.0, "tonne", "tonnes", "metric ton", "metric tons"))
        add(UnitDef("mass", "oz", 28.349523125, "oz", "ounce", "ounces"))
        add(UnitDef("mass", "lb", 453.59237, "lb", "lbs", "pound", "pounds"))
        add(UnitDef("mass", "st", 6350.29318, "stone", "stones"))
        add(UnitDef("mass", "ton", 907_184.74, "ton", "tons", "short ton", "short tons"))
        // Volume, base litre (US measures)
        add(UnitDef("volume", "ml", 0.001, "ml", "milliliter", "milliliters", "millilitre", "millilitres"))
        add(UnitDef("volume", "l", 1.0, "l", "liter", "liters", "litre", "litres"))
        add(UnitDef("volume", "tsp", 0.00492892159375, "tsp", "teaspoon", "teaspoons"))
        add(UnitDef("volume", "tbsp", 0.01478676478125, "tbsp", "tablespoon", "tablespoons"))
        add(UnitDef("volume", "fl oz", 0.0295735295625, "fl oz", "floz", "fluid ounce", "fluid ounces"))
        add(UnitDef("volume", "cup", 0.2365882365, "cup", "cups"))
        add(UnitDef("volume", "pt", 0.473176473, "pt", "pint", "pints"))
        add(UnitDef("volume", "qt", 0.946352946, "qt", "quart", "quarts"))
        add(UnitDef("volume", "gal", 3.785411784, "gal", "gallon", "gallons"))
        // Speed, base metre per second
        add(UnitDef("speed", "m/s", 1.0, "m/s", "mps", "meters per second", "metres per second"))
        add(UnitDef("speed", "km/h", 1.0 / 3.6, "km/h", "kmh", "kph", "kmph", "kilometers per hour", "kilometres per hour"))
        add(UnitDef("speed", "mph", 0.44704, "mph", "miles per hour"))
        add(UnitDef("speed", "kn", 1852.0 / 3600.0, "kn", "knot", "knots", "kt", "kts"))
        add(UnitDef("speed", "ft/s", 0.3048, "ft/s", "fps", "feet per second"))
        // Area, base square metre
        add(UnitDef("area", "m²", 1.0, "m2", "m²", "sqm", "sq m", "square meter", "square meters", "square metre", "square metres"))
        add(UnitDef("area", "cm²", 0.0001, "cm2", "cm²", "sq cm", "square centimeter", "square centimeters", "square centimetre", "square centimetres"))
        add(UnitDef("area", "km²", 1_000_000.0, "km2", "km²", "sq km", "square kilometer", "square kilometers", "square kilometre", "square kilometres"))
        add(UnitDef("area", "ha", 10_000.0, "ha", "hectare", "hectares"))
        add(UnitDef("area", "ft²", 0.09290304, "ft2", "ft²", "sqft", "sq ft", "square foot", "square feet"))
        add(UnitDef("area", "in²", 0.00064516, "in2", "in²", "sq in", "square inch", "square inches"))
        add(UnitDef("area", "yd²", 0.83612736, "yd2", "yd²", "sq yd", "square yard", "square yards"))
        add(UnitDef("area", "mi²", 2_589_988.110336, "mi2", "mi²", "sq mi", "square mile", "square miles"))
        add(UnitDef("area", "acre", 4046.8564224, "acre", "acres", "ac"))
        // Data, base byte (kb, mb ... are the usual thousands; kib, mib ... are the binary ones)
        add(UnitDef("data", "bit", 0.125, "bit", "bits"))
        add(UnitDef("data", "B", 1.0, "byte", "bytes"))
        add(UnitDef("data", "KB", 1e3, "kb", "kilobyte", "kilobytes"))
        add(UnitDef("data", "MB", 1e6, "mb", "megabyte", "megabytes"))
        add(UnitDef("data", "GB", 1e9, "gb", "gigabyte", "gigabytes"))
        add(UnitDef("data", "TB", 1e12, "tb", "terabyte", "terabytes"))
        add(UnitDef("data", "PB", 1e15, "pb", "petabyte", "petabytes"))
        add(UnitDef("data", "KiB", 1024.0, "kib", "kibibyte", "kibibytes"))
        add(UnitDef("data", "MiB", 1024.0 * 1024, "mib", "mebibyte", "mebibytes"))
        add(UnitDef("data", "GiB", 1024.0 * 1024 * 1024, "gib", "gibibyte", "gibibytes"))
        add(UnitDef("data", "TiB", 1024.0 * 1024 * 1024 * 1024, "tib", "tebibyte", "tebibytes"))
        // Time, base second
        add(UnitDef("time", "ms", 0.001, "ms", "millisecond", "milliseconds"))
        add(UnitDef("time", "s", 1.0, "s", "sec", "secs", "second", "seconds"))
        add(UnitDef("time", "min", 60.0, "min", "mins", "minute", "minutes"))
        add(UnitDef("time", "h", 3600.0, "h", "hr", "hrs", "hour", "hours"))
        add(UnitDef("time", "d", 86_400.0, "d", "day", "days"))
        add(UnitDef("time", "wk", 604_800.0, "week", "weeks", "wk", "wks"))
        add(UnitDef("time", "mo", 2_629_746.0, "month", "months"))
        add(UnitDef("time", "yr", 31_556_952.0, "year", "years", "yr", "yrs"))
        // Temperature has its own rules, see [temperature]; the factor is unused.
        add(UnitDef("temperature", "°C", 1.0, "c", "°c", "celsius", "centigrade", "degrees celsius", "degree celsius", "deg c"))
        add(UnitDef("temperature", "°F", 1.0, "f", "°f", "fahrenheit", "degrees fahrenheit", "degree fahrenheit", "deg f"))
        add(UnitDef("temperature", "K", 1.0, "k", "kelvin"))
    }

    // Names that stand for money. A plain "dollar" is the US dollar.
    private val currencyNames = mapOf(
        "dollar" to "USD", "dollars" to "USD", "usd" to "USD", "\$" to "USD", "us dollar" to "USD", "us dollars" to "USD",
        "euro" to "EUR", "euros" to "EUR", "€" to "EUR",
        "pound" to "GBP", "pounds" to "GBP", "£" to "GBP", "sterling" to "GBP", "pound sterling" to "GBP", "quid" to "GBP",
        "rupee" to "INR", "rupees" to "INR", "₹" to "INR",
        "yen" to "JPY", "¥" to "JPY",
        "yuan" to "CNY", "renminbi" to "CNY", "rmb" to "CNY",
        "franc" to "CHF", "francs" to "CHF",
        "won" to "KRW", "baht" to "THB", "peso" to "MXN", "pesos" to "MXN", "rand" to "ZAR", "lira" to "TRY",
        "canadian dollar" to "CAD", "canadian dollars" to "CAD", "australian dollar" to "AUD", "australian dollars" to "AUD",
    )

    private val byName: Map<String, UnitDef> = buildMap {
        for (u in units) for (n in u.names) putIfAbsent(n.lowercase(), u)
    }

    private fun requestPattern(separators: String) = Regex(
        "^\\s*(?:convert\\s+)?(?<n>[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?:e[-+]?\\d+)?|[-+]?\\.\\d+)\\s*(?<from>.+?)\\s+(?:$separators)\\s+(?<to>.+?)\\s*[.?]?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    // "to" is tried first because "in" is also the unit inch ("10 sq in to cm2").
    private val strongRequest = requestPattern("to|into|->|→|=")
    private val weakRequest = requestPattern("to|into|in|as|->|→|=")

    const val USAGE = "Write an amount, a unit and the unit to convert to, for example \"10 km to miles\", \"72 f to c\" or \"100 usd to eur\""

    fun parse(input: String): ConversionRequest {
        val text = fixSignFirst(input.trim())
        val m = strongRequest.find(text) ?: weakRequest.find(text) ?: throw IllegalArgumentException(USAGE)
        val amount = m.groups["n"]!!.value.replace(",", "").toDoubleOrNull() ?: throw IllegalArgumentException(USAGE)
        return ConversionRequest(amount, m.groups["from"]!!.value.trim(), m.groups["to"]!!.value.trim())
    }

    /** "$20 to eur" becomes "20 $ to eur". */
    private fun fixSignFirst(s: String): String {
        val m = Regex("^([\$€£₹¥])\\s*([-+]?[\\d,.]+)(.*)$").find(s) ?: return s
        return "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}"
    }

    /** The ISO currency code for [name]: a known word, or any three letters that are not a unit. Null if it can't be money. */
    fun currencyCode(name: String): String? {
        val n = name.trim().lowercase().trimEnd('.').replace(Regex("\\s+"), " ")
        currencyNames[n]?.let { return it }
        if (n.length == 3 && n.all { it in 'a'..'z' } && byName[n] == null) return n.uppercase()
        return null
    }

    sealed class Result {
        data class Units(val value: Double, val text: String) : Result()
        data class Money(val from: String, val to: String, val amount: Double) : Result()
    }

    /** What [request] means: a unit conversion (worked out here) or money (which needs today's rates). */
    fun resolve(request: ConversionRequest): Result {
        val fromCode = currencyCode(request.from)
        val toCode = currencyCode(request.to)
        if (fromCode != null && toCode != null) return Result.Money(fromCode, toCode, request.amount)

        val from = byName[normal(request.from)] ?: throw IllegalArgumentException("I don't know the unit \"${request.from}\".")
        val to = byName[normal(request.to)] ?: throw IllegalArgumentException("I don't know the unit \"${request.to}\".")
        if (from.category != to.category) {
            throw IllegalArgumentException("${from.symbol} (${from.category}) cannot be converted to ${to.symbol} (${to.category}).")
        }
        val value = if (from.category == "temperature") temperature(request.amount, from.symbol, to.symbol)
        else request.amount * from.factor / to.factor
        return Result.Units(value, "${formatPlain(request.amount)} ${from.symbol} = ${formatPlain(value)} ${to.symbol}")
    }

    private fun normal(name: String) = name.trim().lowercase().trimEnd('.').replace(Regex("\\s+"), " ")

    fun temperature(amount: Double, from: String, to: String): Double {
        val celsius = when (from) {
            "°C" -> amount
            "°F" -> (amount - 32) * 5 / 9
            else -> amount - 273.15
        }
        if (celsius < -273.15 - 1e-9) throw IllegalArgumentException("That is below absolute zero.")
        return when (to) {
            "°C" -> celsius
            "°F" -> celsius * 9 / 5 + 32
            else -> celsius + 273.15
        }
    }

    /** Up to six significant digits, no exponent, no needless zeros. */
    fun formatPlain(value: Double): String {
        if (value == 0.0) return "0"
        val rounded = BigDecimal(value).round(MathContext(6, RoundingMode.HALF_UP)).stripTrailingZeros()
        return rounded.toPlainString()
    }

    /** Money to two decimals, or four significant digits for tiny amounts. */
    fun formatMoney(value: Double): String {
        if (abs(value) >= 1) return BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toPlainString()
        return BigDecimal(value).round(MathContext(4, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
    }
}

/** Exchange rates from the European Central Bank, through frankfurter.dev (free, no key). */
class Rates(val date: String, val perUnit: Map<String, Double>)

object Currency {
    private const val BASE = "https://api.frankfurter.dev/v1/latest?base="
    private const val KEEP_MS = 12 * 60 * 60 * 1000L

    private val cache = HashMap<String, Pair<Long, Rates>>()

    /** Reads the answer of the rates service. */
    fun parseRates(json: String): Rates {
        val root = JSONObject(json)
        val rates = root.optJSONObject("rates") ?: throw IllegalArgumentException("The rates service gave no rates.")
        val map = HashMap<String, Double>()
        for (k in rates.keys()) map[k] = rates.getDouble(k)
        return Rates(root.optString("date"), map)
    }

    /** Rates against [base], fetched through [http] and kept for twelve hours. */
    @Synchronized
    fun rates(base: String, now: Long, http: (String) -> String): Rates {
        cache[base]?.let { (at, r) -> if (now - at in 0 until KEEP_MS) return r }
        val fresh = parseRates(http(BASE + base))
        cache[base] = now to fresh
        return fresh
    }

    @Synchronized
    fun forget() = cache.clear()

    fun convert(from: String, to: String, amount: Double, now: Long, http: (String) -> String): Pair<Double, String> {
        if (from == to) return amount to ""
        val rates = try {
            rates(from, now, http)
        } catch (e: java.io.IOException) {
            // The service answers 404 for a code it does not know.
            if (e.message?.contains("Not found") == true) throw IllegalArgumentException("$from is not a currency I have rates for.")
            throw e
        }
        val rate = rates.perUnit[to] ?: throw IllegalArgumentException("$to is not a currency I have rates for.")
        return amount * rate to rates.date
    }
}
