package com.pocketlm.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonSchemaTest {

    @Test
    fun theExampleIsAUsableSchema() {
        assertNull(JsonSchema.problem(JsonSchema.EXAMPLE))
    }

    @Test
    fun emptyBrokenAndHugeSchemasAreRefused() {
        assertNotNull(JsonSchema.problem("   "))
        assertNotNull(JsonSchema.problem("{\"type\": "))
        assertNotNull(JsonSchema.problem("{}"))
        assertNotNull(JsonSchema.problem("{\"description\":\"" + "x".repeat(JsonSchema.MAX_CHARS) + "\"}"))
    }

    @Test
    fun aSchemaIsReadFromAnApiRequest() {
        val format = JSONObject("""{"type":"json_schema","json_schema":{"name":"x","schema":{"type":"object","properties":{"a":{"type":"string"}}}}}""")
        val schema = JsonSchema.fromResponseFormat(format)
        assertNotNull(schema)
        assertEquals("object", JSONObject(schema!!).getString("type"))
        assertFalse(JsonSchema.wantsJsonObject(format))
    }

    @Test
    fun plainJsonModeIsRecognised() {
        val format = JSONObject("""{"type":"json_object"}""")
        assertTrue(JsonSchema.wantsJsonObject(format))
        assertNull(JsonSchema.fromResponseFormat(format))
        assertNull(JsonSchema.fromResponseFormat(null))
        assertNull(JsonSchema.fromResponseFormat(JSONObject("""{"type":"text"}""")))
    }
}
