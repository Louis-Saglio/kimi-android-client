package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The payload-derived row display values (ticket 120): the Read range chip and the
 * Write size chip's raw numbers; plus the Grep filter-chip derivation (ticket 123)
 * and the meta rows' crop label / status-badge color mapping (ticket 125). */
class ToolPayloadTest {

    @Test
    fun `a full-file read shows no range chip`() {
        assertNull(ToolPayload.Read("/tmp/a.txt", offset = null, limit = null).rangeLabel)
    }

    @Test
    fun `a ranged read shows its lines chip`() {
        assertEquals("lines 40–139", ToolPayload.Read("/tmp/a.txt", offset = 40, limit = 100).rangeLabel)
    }

    @Test
    fun `a limit-only read ranges from the first line`() {
        assertEquals("lines 1–50", ToolPayload.Read("/tmp/a.txt", offset = null, limit = 50).rangeLabel)
    }

    @Test
    fun `an offset-only read is open-ended`() {
        assertEquals("lines 40–end", ToolPayload.Read("/tmp/a.txt", offset = 40, limit = null).rangeLabel)
    }

    @Test
    fun `the write size chip counts lines and utf-8 bytes`() {
        val write = ToolPayload.Write("/tmp/a.txt", "one\ntwo\n", append = false)

        // The trailing newline's empty tail counts (the ticket's `content.lines().size`).
        assertEquals(3, write.lineCount)
        assertEquals(8, write.byteSize)
        // Multi-byte characters size as UTF-8, not chars: "é" is 2 bytes.
        assertEquals(2, ToolPayload.Write("/tmp/a.txt", "é", append = false).byteSize)
        assertEquals(0, ToolPayload.Write("/tmp/a.txt", "", append = false).byteSize)
    }

    // --- Ticket 123: the Grep filter-chip derivation ----------------------------

    private fun grep(
        caseInsensitive: Boolean = false,
        contextBefore: Int? = null,
        contextAfter: Int? = null,
        multiline: Boolean = false,
        includeIgnored: Boolean = false,
        type: String? = null,
    ) = ToolPayload.Grep(
        "foo",
        path = null,
        glob = null,
        outputMode = "content",
        caseInsensitive = caseInsensitive,
        contextBefore = contextBefore,
        contextAfter = contextAfter,
        multiline = multiline,
        includeIgnored = includeIgnored,
        type = type,
    )

    @Test
    fun `a default Grep call earns no chips`() {
        val payload = grep()

        assertEquals(emptyList<String>(), payload.contextChips)
        assertEquals(false, payload.hasNonDefaultKnobs)
    }

    @Test
    fun `symmetric context collapses to a single plus-minus chip`() {
        // The -C 2 shape; -A 2 -B 2 derives the same.
        assertEquals(listOf("±2"), grep(contextBefore = 2, contextAfter = 2).contextChips)
    }

    @Test
    fun `asymmetric context shows the per-side chips`() {
        assertEquals(listOf("+3"), grep(contextAfter = 3).contextChips)
        assertEquals(listOf("-1"), grep(contextBefore = 1).contextChips)
        assertEquals(listOf("+3", "-1"), grep(contextBefore = 1, contextAfter = 3).contextChips)
    }

    @Test
    fun `every non-default knob marks the chips row`() {
        assertEquals(true, grep(caseInsensitive = true).hasNonDefaultKnobs)
        assertEquals(true, grep(contextBefore = 2, contextAfter = 2).hasNonDefaultKnobs)
        assertEquals(true, grep(multiline = true).hasNonDefaultKnobs)
        assertEquals(true, grep(includeIgnored = true).hasNonDefaultKnobs)
        assertEquals(true, grep(type = "kt").hasNonDefaultKnobs)
    }

    // --- Ticket 125: the meta rows' derived display values -------------------------

    @Test
    fun `a full region shows the crop label`() {
        assertEquals(
            "crop 100×200",
            ToolPayload.ReadMedia("/tmp/a.png", regionWidth = 100, regionHeight = 200).cropLabel,
        )
    }

    @Test
    fun `a media read without a full region shows no crop label`() {
        assertNull(ToolPayload.ReadMedia("/tmp/a.png", regionWidth = null, regionHeight = null).cropLabel)
        assertNull(ToolPayload.ReadMedia("/tmp/a.png", regionWidth = 100, regionHeight = null).cropLabel)
    }

    /** The UpdateGoal badge's color mapping (ticket 125): success for `complete`,
     * error for `blocked`, primary for `active`, neutral for a drifted value. */
    @Test
    fun `the update goal badge color maps per status`() {
        assertEquals(ToolPayload.UpdateGoal.BadgeColor.SUCCESS, ToolPayload.UpdateGoal("complete").badgeColor)
        assertEquals(ToolPayload.UpdateGoal.BadgeColor.ERROR, ToolPayload.UpdateGoal("blocked").badgeColor)
        assertEquals(ToolPayload.UpdateGoal.BadgeColor.PRIMARY, ToolPayload.UpdateGoal("active").badgeColor)
        assertEquals(ToolPayload.UpdateGoal.BadgeColor.NEUTRAL, ToolPayload.UpdateGoal("paused").badgeColor)
    }
}
