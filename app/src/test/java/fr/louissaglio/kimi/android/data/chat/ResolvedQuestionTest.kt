package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parser coverage for [ResolvedQuestionParser] (ticket 089): the resolved
 * AskUserQuestion row's input/output strings → the read-only card model. */
class ResolvedQuestionTest {

    private val input = """
        {"questions":[{"question":"Red or blue?","header":"Color",
        "options":[{"label":"red","description":"warm color"},{"label":"blue","description":"cool color"}],
        "multi_select":false}]}
    """.trimIndent()

    @Test
    fun `answered with an option pick marks the selected label`() {
        val parsed = ResolvedQuestionParser.parse("tool_q1", input, """{"answers":{"Red or blue?":"blue"}}""")!!

        assertEquals("tool_q1", parsed.toolCallId)
        assertNull(parsed.note)
        val item = parsed.items.single()
        assertEquals("Color", item.header)
        assertEquals("Red or blue?", item.text)
        assertEquals(
            listOf(
                ResolvedQuestion.Option("red", "warm color"),
                ResolvedQuestion.Option("blue", "cool color"),
            ),
            item.options,
        )
        assertEquals(listOf("blue"), item.selectedLabels)
        assertNull(item.otherText)
    }

    @Test
    fun `answered with free text lands in other text`() {
        val parsed = ResolvedQuestionParser.parse("tool_q1", input, """{"answers":{"Red or blue?":"green"}}""")!!

        val item = parsed.items.single()
        assertTrue(item.selectedLabels.isEmpty())
        assertEquals("green", item.otherText)
    }

    @Test
    fun `multi item question resolves each item by its question text`() {
        val multiInput = """
            {"questions":[
            {"question":"Red or blue?","header":"Color","options":[{"label":"red"},{"label":"blue"}],"multi_select":false},
            {"question":"Fast or safe?","header":"Mode","options":[{"label":"fast"},{"label":"safe"}],"multi_select":false}]}
        """.trimIndent()
        val parsed = ResolvedQuestionParser.parse(
            "tool_q1",
            multiInput,
            """{"answers":{"Red or blue?":"red","Fast or safe?":"carefully"}}""",
        )!!

        assertEquals(2, parsed.items.size)
        assertEquals(listOf("red"), parsed.items[0].selectedLabels)
        assertTrue(parsed.items[1].selectedLabels.isEmpty())
        assertEquals("carefully", parsed.items[1].otherText)
    }

    @Test
    fun `legacy transcripts key answers by the synthesized item id`() {
        val parsed = ResolvedQuestionParser.parse("tool_q1", input, """{"answers":{"q_0":"red"}}""")!!

        assertEquals(listOf("red"), parsed.items.single().selectedLabels)
    }

    @Test
    fun `dismissed carries the note and no selections`() {
        val parsed = ResolvedQuestionParser.parse(
            "tool_q1",
            input,
            """{"answers":{},"note":"User dismissed the question without answering."}""",
        )!!

        assertTrue(parsed.dismissed)
        assertEquals("User dismissed the question without answering.", parsed.note)
        assertTrue(parsed.items.single().selectedLabels.isEmpty())
        assertNull(parsed.items.single().otherText)
    }

    @Test
    fun `unknown fields are ignored`() {
        val parsed = ResolvedQuestionParser.parse(
            "tool_q1",
            """{"questions":[{"question":"Red or blue?","header":"Color","options":[{"label":"red"},{"label":"blue"}],"multi_select":false}],"background":false}""",
            """{"answers":{"Red or blue?":"red"},"extra":"field"}""",
        )!!

        assertEquals(listOf("red"), parsed.items.single().selectedLabels)
    }

    @Test
    fun `malformed input or output yields null`() {
        assertNull(ResolvedQuestionParser.parse("tool_q1", "not json", """{"answers":{}}"""))
        assertNull(ResolvedQuestionParser.parse("tool_q1", input, "task_id: 123\nstatus: running"))
        assertNull(ResolvedQuestionParser.parse("tool_q1", """{"questions":[]}""", """{"unexpected":true}"""))
        assertNull(
            ResolvedQuestionParser.parse(
                "tool_q1",
                """{"questions":[{"header":"Color","options":[{"label":"red"}]}]}""",
                """{"answers":{}}""",
            ),
        )
    }

    @Test
    fun `card model synthesizes wire-convention ids and prefills the drafts`() {
        val parsed = ResolvedQuestionParser.parse(
            "tool_q1",
            """{"questions":[
            {"question":"Red or blue?","header":"Color","options":[{"label":"red"},{"label":"blue"}],"multi_select":false},
            {"question":"Food?","header":"Meal","options":[{"label":"sushi"},{"label":"pasta"}],"multi_select":false}]}""",
            """{"answers":{"Red or blue?":"blue","Food?":"ramen"}}""",
        )!!

        val card = parsed.asCardModel()
        assertEquals("tool_q1", card.question.questionId)
        assertEquals(listOf("q_0", "q_1"), card.question.items.map { it.id })
        assertEquals(listOf("opt_0_0", "opt_0_1"), card.question.items[0].options.map { it.id })
        assertEquals(mapOf("q_0" to setOf("opt_0_1")), card.selections)
        assertEquals(mapOf("q_1" to "ramen"), card.otherTexts)
        // The free-text item renders its answer field; the option-picked one does not.
        assertEquals(listOf(false, true), card.question.items.map { it.allowOther })
    }

    private val multiInput = """
        {"questions":[{"question":"Colors?","header":"Palette",
        "options":[{"label":"Red"},{"label":"Green"},{"label":"Blue"}],
        "multi_select":true}]}
    """.trimIndent()

    @Test
    fun `multi answer splits the comma-joined labels into selections`() {
        val parsed = ResolvedQuestionParser.parse("tool_q1", multiInput, """{"answers":{"Colors?":"Red, Blue"}}""")!!

        val item = parsed.items.single()
        assertTrue(item.multiSelect)
        assertEquals(listOf("Red", "Blue"), item.selectedLabels)
        assertNull(item.otherText)
    }

    @Test
    fun `multi with other splits matched labels and rejoins the free text`() {
        val parsed = ResolvedQuestionParser.parse("tool_q1", multiInput, """{"answers":{"Colors?":"Red, Purple, Blue"}}""")!!

        val item = parsed.items.single()
        assertEquals(listOf("Red", "Blue"), item.selectedLabels)
        assertEquals("Purple", item.otherText)
    }

    @Test
    fun `multi item answered with a single tick has no other text`() {
        val parsed = ResolvedQuestionParser.parse("tool_q1", multiInput, """{"answers":{"Colors?":"Green"}}""")!!

        val item = parsed.items.single()
        assertEquals(listOf("Green"), item.selectedLabels)
        assertNull(item.otherText)
    }

    @Test
    fun `multi absent flag parses as single select`() {
        val parsed = ResolvedQuestionParser.parse(
            "tool_q1",
            """{"questions":[{"question":"Colors?","options":[{"label":"Red"},{"label":"Blue"}]}]}""",
            """{"answers":{"Colors?":"Red"}}""",
        )!!

        val item = parsed.items.single()
        assertTrue(!item.multiSelect)
        assertEquals(listOf("Red"), item.selectedLabels)
    }

    @Test
    fun `single select free text containing a comma stays whole`() {
        // No regression of the 089 single-select path: a comma in a free-text answer
        // must not be split when the item is not multi-select.
        val parsed = ResolvedQuestionParser.parse("tool_q1", input, """{"answers":{"Red or blue?":"red, but dark"}}""")!!

        val item = parsed.items.single()
        assertTrue(item.selectedLabels.isEmpty())
        assertEquals("red, but dark", item.otherText)
    }

    @Test
    fun `card model of a resolved multi question checks the matched options`() {
        val parsed = ResolvedQuestionParser.parse("tool_q1", multiInput, """{"answers":{"Colors?":"Red, Purple"}}""")!!

        val card = parsed.asCardModel()
        assertEquals(listOf(true), card.question.items.map { it.multiSelect })
        assertEquals(mapOf("q_0" to setOf("opt_0_0")), card.selections)
        assertEquals(mapOf("q_0" to "Purple"), card.otherTexts)
    }
}
