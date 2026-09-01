package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** The generic detail table's field derivation (ticket 118). */
class ToolDetailFieldsTest {

    @Test
    fun `null or empty args yield no fields`() {
        assertEquals(emptyList<ToolDetailFields.Field>(), ToolDetailFields.of(null))
        assertEquals(emptyList<ToolDetailFields.Field>(), ToolDetailFields.of(buildJsonObject { }))
    }

    @Test
    fun `top-level entries keep the wire order`() {
        val args = buildJsonObject {
            put("zeta", 1)
            put("alpha", "two")
            put("mid", true)
        }

        assertEquals(
            listOf(
                ToolDetailFields.Field("zeta", "1"),
                ToolDetailFields.Field("alpha", "two"),
                ToolDetailFields.Field("mid", "true"),
            ),
            ToolDetailFields.of(args),
        )
    }

    @Test
    fun `a string value renders raw and whitespace-collapsed`() {
        val args = buildJsonObject { put("command", "echo one\necho   two") }

        assertEquals(listOf(ToolDetailFields.Field("command", "echo one echo two")), ToolDetailFields.of(args))
    }

    @Test
    fun `number and boolean values render literal`() {
        val args = buildJsonObject {
            put("timeout", 60)
            put("ratio", 0.5)
            put("run_in_background", true)
        }

        assertEquals(
            listOf(
                ToolDetailFields.Field("timeout", "60"),
                ToolDetailFields.Field("ratio", "0.5"),
                ToolDetailFields.Field("run_in_background", "true"),
            ),
            ToolDetailFields.of(args),
        )
    }

    @Test
    fun `null values are dropped`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike")
            put("offset", JsonNull)
        }

        assertEquals(listOf(ToolDetailFields.Field("path", "/tmp/spike")), ToolDetailFields.of(args))
    }

    @Test
    fun `nested objects and arrays render as compact one-line json`() {
        val args = buildJsonObject {
            put("questions", buildJsonArray { add(buildJsonObject { put("id", "q_0") }) })
            put("config", buildJsonObject { put("mode", "fast") })
        }

        assertEquals(
            listOf(
                ToolDetailFields.Field("questions", """[{"id":"q_0"}]"""),
                ToolDetailFields.Field("config", """{"mode":"fast"}"""),
            ),
            ToolDetailFields.of(args),
        )
    }
}
