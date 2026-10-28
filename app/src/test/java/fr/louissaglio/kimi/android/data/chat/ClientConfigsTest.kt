package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The client-config decode and rule matching (tickets 167/170), against the live
 * 2.3.3 payload shapes recorded in docs/server-api.md. */
class ClientConfigsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(body: String): ClientConfigDto? =
        json.decodeFromString<ClientConfigData>(body).config

    private val modelSwitchConfig = decode(MODEL_SWITCH_BODY)!!.toSwitchConfirm()
    private val effortConfig = decode(EFFORT_SWITCH_BODY)!!.toEffortSwitchConfig()

    // --- Matching (ticket 167) -------------------------------------------------

    @Test
    fun `a string rule matches an exact upstream id`() {
        val config = decode(
            """{"config":{"enable":true,"rules":[{"from":"k3","to":"k3.1-pro","session_type":"all"}]}}""",
        )!!.toSwitchConfirm()

        assertTrue(config.firstMatch("k3", "k3.1-pro", SessionAge.New) != null)
        assertNull(config.firstMatch("k3-256k", "k3.1-pro", SessionAge.New))
        assertNull(config.firstMatch("k3", "kimi-for-coding", SessionAge.New))
    }

    @Test
    fun `an array rule matches any listed id and the wildcard matches everything`() {
        val config = decode(
            """{"config":{"enable":true,"rules":[{"from":["k3","kimi-for-coding"],"to":"*","session_type":"all"}]}}""",
        )!!.toSwitchConfirm()

        assertTrue(config.firstMatch("k3", "anything", SessionAge.New) != null)
        assertTrue(config.firstMatch("kimi-for-coding", "anything", SessionAge.Old) != null)
        assertNull(config.firstMatch("deepseek-v4-pro", "anything", SessionAge.New))
    }

    @Test
    fun `session_type old new and all select the matching age`() {
        val config = decode(
            """{"config":{"enable":true,"rules":[""" +
                """{"from":"*","to":"*","session_type":"new"},""" +
                """{"from":"*","to":"*","session_type":"old"},""" +
                """{"from":"*","to":"*","session_type":"all"}]}}""",
        )!!.toSwitchConfirm()

        assertEquals("new", config.firstMatch("a", "b", SessionAge.New)?.sessionType)
        assertEquals("old", config.firstMatch("a", "b", SessionAge.Old)?.sessionType)
    }

    @Test
    fun `a disabled config never matches`() {
        val config = decode(
            """{"config":{"enable":false,"rules":[{"from":"*","to":"*","session_type":"all"}]}}""",
        )!!.toSwitchConfirm()

        assertNull(config.firstMatch("a", "b", SessionAge.New))
    }

    @Test
    fun `rules missing a valid session_type are dropped`() {
        val config = decode(
            """{"config":{"enable":true,"rules":[""" +
                """{"from":"*","to":"*"},""" +
                """{"from":"*","to":"*","session_type":"sometimes"},""" +
                """{"from":"*","to":"*","session_type":"all"}]}}""",
        )!!.toSwitchConfirm()

        assertEquals(1, config.rules.size)
    }

    @Test
    fun `a missing alias on either side never matches`() {
        assertNull(modelSwitchConfig.firstMatch(null, "k3.1-pro", SessionAge.New))
        assertNull(modelSwitchConfig.firstMatch("k3", null, SessionAge.New))
    }

    @Test
    fun `the live model rules gate only their listed targets and session types`() {
        // The capture's first rule is `*` → k3.1-pro-highspeed for NEW sessions.
        assertTrue(modelSwitchConfig.firstMatch("k3", "k3.1-pro-highspeed", SessionAge.New) != null)
        // The old-session twin exists, but with its own hint/warning — assert it is
        // the old rule, not the new one.
        val oldRule = modelSwitchConfig.firstMatch("k3", "k3.1-pro-highspeed", SessionAge.Old)
        assertEquals("old", oldRule?.sessionType)
        assertTrue(oldRule?.warning != null)
        // A target outside every rule keeps selection immediate.
        assertNull(modelSwitchConfig.firstMatch("k3", "deepseek-v4-pro", SessionAge.Old))
    }

    // --- Effort config (ticket 170) --------------------------------------------

    @Test
    fun `the live effort config carries the English descriptions and the old-session rule`() {
        assertEquals(
            "Light reasoning for simple tasks and quick iterations",
            effortConfig.descriptions["low"],
        )
        assertEquals(
            "Maximum reasoning for the hardest tasks, with longer response times and higher token usage",
            effortConfig.descriptions["max"],
        )
        assertTrue(effortConfig.confirm.firstMatch("low", "max", SessionAge.Old) != null)
        assertNull(effortConfig.confirm.firstMatch("low", "max", SessionAge.New))
        // Chinese strings are never applied.
        assertNull(effortConfig.descriptions["zh"])
    }

    @Test
    fun `disabled descriptions are dropped and a null confirm disables the gate`() {
        val disabled = decode(
            """{"config":{"descriptions":{"enable":false,"items":{"low":{"en":"nope"}}},"confirm":null}}""",
        )!!.toEffortSwitchConfig()

        assertTrue(disabled.descriptions.isEmpty())
        assertNull(disabled.confirm.firstMatch("*", "*", SessionAge.Old))
    }

    @Test
    fun `a malformed config is a null policy at the envelope level`() {
        assertNull(decode("""{"name":"model_switch_confirm","config":null}"""))
    }

    private companion object {
        // Verbatim live 2.3.3 capture, Chinese strings trimmed (docs/server-api.md).
        const val MODEL_SWITCH_BODY =
            """{"name":"model_switch_confirm","config":{"enable":true,"rules":[""" +
                """{"from":"*","to":["k3.1-pro-highspeed"],"session_type":"new","hint":{"en":"Note: 2x consumption"}},""" +
                """{"from":"*","to":["k3.1-pro-highspeed"],"session_type":"old","hint":{"en":"May affect quality"},""" +
                """"warning":{"en":"Note: 2x consumption"}},""" +
                """{"from":["k3","k3-256k","kimi-for-coding"],"to":["k3.1-pro","k3.1-pro-highspeed"],""" +
                """"session_type":"old","hint":{"en":"Series switch"}}]}}"""

        // Verbatim live 2.3.3 capture, Chinese strings trimmed (docs/server-api.md).
        const val EFFORT_SWITCH_BODY =
            """{"name":"effort_switch_confirm","config":{"descriptions":{"enable":true,"items":{""" +
                """"low":{"en":"Light reasoning for simple tasks and quick iterations"},""" +
                """"high":{"en":"Deeper reasoning for complex tasks, with higher token usage"},""" +
                """"max":{"en":"Maximum reasoning for the hardest tasks, with longer response times and higher token usage"}}},""" +
                """"confirm":{"enable":true,"rules":[{"from":"*","to":"*","session_type":"old",""" +
                """"hint":{"en":"Switching thinking effort may affect output quality"}}]}}}"""
    }
}
