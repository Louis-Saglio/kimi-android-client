package fr.louissaglio.kimi.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** The FetchURL row's host-first preview (ticket 122). */
class HostFirstUrlTest {

    @Test
    fun `a deep URL shortens to host plus first path segment`() {
        assertEquals("example.com/docs/…", hostFirstUrl("https://example.com/docs/guide/install.html"))
    }

    @Test
    fun `a bare host stays bare`() {
        assertEquals("example.com", hostFirstUrl("https://example.com"))
        assertEquals("example.com", hostFirstUrl("https://example.com/"))
    }

    @Test
    fun `a single-segment path shows in full`() {
        assertEquals("example.com/docs", hostFirstUrl("https://example.com/docs"))
    }

    @Test
    fun `a query string does not force the ellipsis`() {
        assertEquals("example.com/search", hostFirstUrl("https://example.com/search?q=kimi"))
    }

    @Test
    fun `a non-default port rides the host`() {
        assertEquals("127.0.0.1:8443/api/…", hostFirstUrl("https://127.0.0.1:8443/api/v1/sessions"))
    }

    @Test
    fun `a URL without a parseable host renders one-lined as-is`() {
        assertEquals("example.com/docs/x", hostFirstUrl("example.com/docs/x"))
        assertEquals("not a url", hostFirstUrl("not a url"))
        assertEquals("multi line garbage", hostFirstUrl("multi\nline\ngarbage"))
    }
}
