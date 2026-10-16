package com.pocketlm.app.data

import com.arm.aichat.GenerationParams
import org.json.JSONObject

/**
 * Settings that belong to one model: optionally its own generation settings, system prompt, chat template and
 * draft model. While the model is loaded these replace the app-wide ones; anything left unset follows them.
 */
data class ModelSettings(
    /** Its own generation settings, or null to follow the app-wide ones. */
    val params: GenerationParams? = null,
    val presetId: String? = null,
    /** Name of a built-in chat template, or empty to use the one inside the model. */
    val chatTemplate: String = "",
    /** File name of a small model that drafts tokens for this one, or null for none. */
    val draftFile: String? = null,
)

object ModelSettingsStore {

    fun toJson(all: Map<String, ModelSettings>): String {
        val root = JSONObject()
        for ((file, s) in all) {
            val entry = JSONObject()
                .put("preset", s.presetId ?: JSONObject.NULL)
                .put("template", s.chatTemplate)
                .put("draft", s.draftFile ?: JSONObject.NULL)
            s.params?.let { p ->
                entry.put("ctx", p.contextSize)
                    .put("threads", p.threads)
                    .put("temp", p.temperature.toDouble())
                    .put("topP", p.topP.toDouble())
                    .put("topK", p.topK)
                    .put("minP", p.minP.toDouble())
                    .put("repeat", p.repeatPenalty.toDouble())
                    .put("seed", p.seed)
            }
            root.put(file, entry)
        }
        return root.toString()
    }

    fun fromJson(json: String?): Map<String, ModelSettings> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(json)
            val defaults = GenerationParams()
            buildMap {
                for (file in root.keys()) {
                    val o = root.getJSONObject(file)
                    put(
                        file,
                        ModelSettings(
                            if (!o.has("ctx")) null else GenerationParams(
                                contextSize = o.optInt("ctx", defaults.contextSize),
                                threads = o.optInt("threads", defaults.threads),
                                temperature = o.optDouble("temp", defaults.temperature.toDouble()).toFloat(),
                                topP = o.optDouble("topP", defaults.topP.toDouble()).toFloat(),
                                topK = o.optInt("topK", defaults.topK),
                                minP = o.optDouble("minP", defaults.minP.toDouble()).toFloat(),
                                repeatPenalty = o.optDouble("repeat", defaults.repeatPenalty.toDouble()).toFloat(),
                                seed = o.optInt("seed", defaults.seed),
                            ),
                            if (o.isNull("preset")) null else o.optString("preset").ifEmpty { null },
                            o.optString("template", ""),
                            if (o.isNull("draft")) null else o.optString("draft").ifEmpty { null },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }
}
