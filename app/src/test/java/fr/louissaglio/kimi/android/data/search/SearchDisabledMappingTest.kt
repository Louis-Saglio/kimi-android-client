package fr.louissaglio.kimi.android.data.search

import fr.louissaglio.kimi.android.data.DataError
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticket 183: the mapper that recognises the server's "global search is
 * disabled" 50001 (docs/server-api.md, Search) — and nothing else. */
class SearchDisabledMappingTest {

    @Test
    fun `the 2_5_0 default-off answer is recognised`() {
        val error = DataError.Api(
            50001,
            "global search is disabled; set [database] global_search = true or " +
                "KIMI_CODE_GLOBAL_SEARCH=1 to enable it",
        )
        assertTrue(error.isGlobalSearchDisabled())
    }

    @Test
    fun `another 50001 stays generic`() {
        assertFalse(DataError.Api(50001, "internal error").isGlobalSearchDisabled())
        // The prefix must lead the message, not merely appear in it.
        assertFalse(DataError.Api(50001, "oops: global search is disabled").isGlobalSearchDisabled())
    }

    @Test
    fun `the prefix under another code stays generic`() {
        assertFalse(DataError.Api(40001, "global search is disabled").isGlobalSearchDisabled())
    }

    @Test
    fun `non-envelope errors stay generic`() {
        assertFalse(DataError.Http(500).isGlobalSearchDisabled())
        assertFalse(DataError.Network(IOException("boom")).isGlobalSearchDisabled())
        assertFalse(DataError.Auth.isGlobalSearchDisabled())
    }
}
