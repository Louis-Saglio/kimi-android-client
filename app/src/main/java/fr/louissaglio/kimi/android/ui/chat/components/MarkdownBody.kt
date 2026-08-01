package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import kotlinx.coroutines.runBlocking

/**
 * Message-body markdown (ticket 020), themed for web parity (ticket 021):
 * inline code as monospace on a rounded chip, fenced code as monospace on a
 * darker inset surface (horizontally scrollable, built into the library's
 * `MarkdownCode`), heading scale capped so a `#`/`##` does not fill the screen,
 * accent-colored underlined links.
 *
 * Streaming cost (tickets 073, 082): the renderer 0.42+ `StreamingMarkdownState`
 * parses INCREMENTALLY — stable blocks parse once, only the still-growing tail
 * re-parses per append, replacing the old full-document re-parse per streamed
 * snapshot. The timeline publishes full text snapshots, the state is append-only,
 * so [StreamingMarkdownFeed] classifies each snapshot as append/no-op/reset
 * (message swap in this slot, history replace) and the state is re-seeded via the
 * [generation] key on reset. The `DeltaCoalescer` 100 ms window stays: it now
 * bounds recomposition/layout churn per emission, not parse cost.
 */
@Composable
internal fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    // Alpha-black so chips/code surfaces read as a subtle darkening of whatever
    // bubble they sit in — a fixed dark color was a hard near-black blob.
    val codeSurface = Color.Black.copy(alpha = 0.3f)
    var generation by remember { mutableIntStateOf(0) }
    val feed = remember(generation) { StreamingMarkdownFeed() }
    if (feed.classify(text) == StreamingMarkdownFeed.Action.Reset) {
        generation++ // converges next frame: the fresh feed classifies the same text as Append
    }
    val state = key(generation) { rememberStreamingMarkdownState() }
    if (feed.fed.isEmpty() && text.isNotEmpty()) {
        // Fresh state: seed synchronously in composition (the 073 first-frame
        // contract — MessageList's bottom pin measures real row heights, a
        // zero-height first frame would detach it). Same cost profile as the old
        // immediate=true: one full parse of the initial content.
        runBlocking { state.append(text) }
        feed.markFed(text)
    }
    val latestText by rememberUpdatedState(text)
    LaunchedEffect(state) {
        // Streaming appends: sequential (never cancelled mid-append), latest-wins
        // (snapshotFlow conflates; skipped snapshots are prefixes of the latest).
        // No-op once the synchronous seed has fed the current text.
        snapshotFlow { latestText }.collect { target ->
            when (val action = feed.classify(target)) {
                is StreamingMarkdownFeed.Action.Append -> {
                    state.append(action.chunk)
                    feed.markFed(target)
                }
                else -> Unit // Reset is handled by the generation bump in composition
            }
        }
    }
    Markdown(
        streamingMarkdownState = state,
        modifier = modifier,
        colors = markdownColor(
            text = LocalContentColor.current,
            codeBackground = codeSurface,
            inlineCodeBackground = codeSurface,
        ),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.headlineSmall,
            h2 = MaterialTheme.typography.titleLarge,
            h3 = MaterialTheme.typography.titleMedium,
            h4 = MaterialTheme.typography.titleSmall,
            h5 = MaterialTheme.typography.titleSmall,
            h6 = MaterialTheme.typography.titleSmall,
            text = MaterialTheme.typography.bodyMedium,
            paragraph = MaterialTheme.typography.bodyMedium,
            ordered = MaterialTheme.typography.bodyMedium,
            bullet = MaterialTheme.typography.bodyMedium,
            list = MaterialTheme.typography.bodyMedium,
            textLink = TextLinkStyles(
                style = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
        ),
        padding = markdownPadding(indentList = 16.dp),
        // Chat markdown treats a single \n as a line break (pre-0.32 library
        // behavior); the GFM-correct EOL-as-space default reads as mangled
        // line breaks in assistant messages.
        annotator = markdownAnnotator(config = markdownAnnotatorConfig(eolAsNewLine = true)),
        // A fresh ExtendedSpans per text node, as the library intends: the class
        // holds per-node draw state, so a shared instance scrambles chip positions
        // across paragraphs.
        extendedSpans = markdownExtendedSpans(
            extendedSpans = { ExtendedSpans(RoundedCornerSpanPainter()) },
        ),
    )
}
