package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * Message-body markdown (ticket 020), themed for web parity (ticket 021):
 * inline code as monospace on a rounded chip, fenced code as monospace on a
 * darker inset surface (horizontally scrollable, built into the library's
 * `MarkdownCode`), heading scale capped so a `#`/`##` does not fill the screen,
 * accent-colored underlined links.
 *
 * Streaming cost (ticket 073): since renderer 0.33.0 the parse is asynchronous
 * (`Dispatchers.Default`) and memoized — recomposing with unchanged text no
 * longer re-parses. `retainState` keeps the previous snapshot on screen while a
 * changed text re-parses, so streamed emissions never flash the blank loading
 * slot. Each new text snapshot still costs one full re-parse of the document
 * (incremental `StreamingMarkdownState` only exists from 0.42.0, which needs
 * Kotlin 2.4), so the `DeltaCoalescer` 100 ms window still bounds the re-parse
 * rate during streaming.
 */
@Composable
internal fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    // Alpha-black so chips/code surfaces read as a subtle darkening of whatever
    // bubble they sit in — a fixed dark color was a hard near-black blob.
    val codeSurface = Color.Black.copy(alpha = 0.3f)
    // immediate: parse the initial content synchronously in composition (as
    // 0.26.0 always did), so the first frame already has real row heights —
    // MessageList's bottom pin measures against them, and a zero-height loading
    // placeholder would let LazyColumn clamp the initial scroll and detach the
    // pin. Content CHANGES (streaming) still re-parse asynchronously through the
    // state's LaunchedEffect, so retainState below stays meaningful.
    val state = rememberMarkdownState(content = text, retainState = true, immediate = true)
    Markdown(
        markdownState = state,
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
