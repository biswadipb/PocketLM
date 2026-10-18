package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject

/** One speed test of a model on this phone. Speeds are tokens per second. */
data class BenchResult(
    val model: String,
    val promptSpeed: Double?,
    val generationSpeed: Double?,
    val takenAt: Long,
) {
    companion object {
        /** Reads the "pp" and "tg" rows of the table the engine returns, e.g. `| ... | pp 128 | 45.2 ± 0.3 |`. */
        fun parseSpeeds(output: String): Pair<Double?, Double?> {
            fun speed(kind: String): Double? =
                Regex("""\|\s*$kind\s+\d+\s*\|\s*([0-9.]+)""").find(output)?.groupValues?.get(1)?.toDoubleOrNull()
            return speed("pp") to speed("tg")
        }

        fun toJson(results: List<BenchResult>): String = JSONArray(results.map {
            JSONObject().put("model", it.model).put("at", it.takenAt)
                .put("pp", it.promptSpeed ?: JSONObject.NULL).put("tg", it.generationSpeed ?: JSONObject.NULL)
        }).toString()

        fun fromJson(json: String?): List<BenchResult> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                List(arr.length()) {
                    val o = arr.getJSONObject(it)
                    BenchResult(
                        o.getString("model"),
                        if (o.isNull("pp")) null else o.getDouble("pp"),
                        if (o.isNull("tg")) null else o.getDouble("tg"),
                        o.getLong("at"),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}
