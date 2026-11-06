package fr.louissaglio.kimi.android.data

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticket 185 (A2): wire parses drop malformed input, never a programming error. */
class ParseOrNullTest {

    @Serializable
    private data class Payload(val id: String)

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a valid parse returns its value and never reports a failure`() {
        var failures = 0
        assertEquals(Payload("a"), parseOrNull(onFailure = { failures++ }) { json.decodeFromString<Payload>("""{"id":"a"}""") })
        assertEquals(0, failures)
    }

    @Test
    fun `malformed wire input is dropped and reported`() {
        val failures = mutableListOf<RuntimeException>()
        val report: (RuntimeException) -> Unit = { failures += it }

        assertNull(parseOrNull(report) { json.decodeFromString<Payload>("{not json") })
        assertNull(parseOrNull(report) { json.decodeFromString<Payload>("""{"other":1}""") })
        assertNull(parseOrNull(report) { json.parseToJsonElement("[1]").jsonObject })
        assertNull(parseOrNull(report) { Instant.parse("yesterday") })
        assertEquals(4, failures.size)
    }

    @Test
    fun `a failure that is not a parse error propagates`() {
        val bug = IllegalStateException("mapper bug")
        val thrown = runCatching { parseOrNull { throw bug } }.exceptionOrNull()
        assertSame(bug, thrown)

        val error = runCatching { parseOrNull<Unit> { throw StackOverflowError() } }.exceptionOrNull()
        assertTrue(error is StackOverflowError)
    }
}
