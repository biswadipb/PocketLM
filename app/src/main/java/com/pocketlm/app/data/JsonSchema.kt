package com.pocketlm.app.data

import org.json.JSONException
import org.json.JSONObject

/** Checks a JSON Schema typed by the user before it is handed to the model's sampler. */
object JsonSchema {
    const val MAX_CHARS = 20_000

    const val EXAMPLE = """{
  "type": "object",
  "properties": {
    "name": { "type": "string" },
    "age": { "type": "integer" }
  },
  "required": ["name", "age"]
}"""

    /** Null when [text] is a usable schema, otherwise a short explanation. */
    fun problem(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "Enter a schema, or turn the schema off."
        if (trimmed.length > MAX_CHARS) return "The schema is too long."
        val parsed = try {
            JSONObject(trimmed)
        } catch (e: JSONException) {
            return "This is not valid JSON: ${e.message?.substringBefore(" at ")?.take(80)}"
        }
        if (parsed.length() == 0) return "The schema is empty."
        return null
    }

    /** Reads `response_format` from an API request: a schema, or null for no schema. */
    fun fromResponseFormat(format: JSONObject?): String? {
        if (format == null || format.optString("type") != "json_schema") return null
        val holder = format.optJSONObject("json_schema") ?: return null
        val schema = holder.optJSONObject("schema") ?: return null
        return schema.toString()
    }

    /** True when an API request asks for plain "JSON mode". */
    fun wantsJsonObject(format: JSONObject?): Boolean = format?.optString("type") == "json_object"
}
