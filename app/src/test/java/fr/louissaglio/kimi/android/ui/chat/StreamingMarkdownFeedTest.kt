package fr.louissaglio.kimi.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingMarkdownFeedTest {

    @Test
    fun `first text is a full append from empty`() {
        val feed = StreamingMarkdownFeed()
        assertEquals(StreamingMarkdownFeed.Action.Append("hello"), feed.classify("hello"))
    }

    @Test
    fun `same text is a no-op`() {
        val feed = StreamingMarkdownFeed()
        feed.markFed("hello")
        assertEquals(StreamingMarkdownFeed.Action.None, feed.classify("hello"))
    }

    @Test
    fun `growing snapshot yields only the suffix`() {
        val feed = StreamingMarkdownFeed()
        feed.markFed("hello")
        assertEquals(StreamingMarkdownFeed.Action.Append(" world"), feed.classify("hello world"))
    }

    @Test
    fun `replaced text resets`() {
        val feed = StreamingMarkdownFeed()
        feed.markFed("hello")
        assertEquals(StreamingMarkdownFeed.Action.Reset, feed.classify("goodbye"))
    }

    @Test
    fun `shrinking text resets`() {
        val feed = StreamingMarkdownFeed()
        feed.markFed("hello world")
        assertEquals(StreamingMarkdownFeed.Action.Reset, feed.classify("hello"))
    }

    @Test
    fun `text differing only mid-string resets`() {
        val feed = StreamingMarkdownFeed()
        feed.markFed("hello world")
        assertEquals(StreamingMarkdownFeed.Action.Reset, feed.classify("hello there"))
    }

    @Test
    fun `after reset the re-seeded state appends again`() {
        val feed = StreamingMarkdownFeed()
        feed.markFed("hello")
        assertEquals(StreamingMarkdownFeed.Action.Reset, feed.classify("goodbye"))
        // The composition discards the state, re-seeds with the full text, then continues.
        val fresh = StreamingMarkdownFeed()
        assertEquals(StreamingMarkdownFeed.Action.Append("goodbye"), fresh.classify("goodbye"))
        fresh.markFed("goodbye")
        assertEquals(StreamingMarkdownFeed.Action.Append("!"), fresh.classify("goodbye!"))
    }

    @Test
    fun `empty text after content resets`() {
        val feed = StreamingMarkdownFeed()
        feed.markFed("hello")
        assertEquals(StreamingMarkdownFeed.Action.Reset, feed.classify(""))
    }

    @Test
    fun `empty text on a fresh feed is a no-op`() {
        val feed = StreamingMarkdownFeed()
        assertEquals(StreamingMarkdownFeed.Action.None, feed.classify(""))
    }
}
