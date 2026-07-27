package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownPadding

/**
 * Message-body markdown (ticket 020), themed for web parity (ticket 021):
 * inline code as monospace on a rounded chip, fenced code as monospace on a
 * darker inset surface (horizontally scrollable, built into the library's
 * `MarkdownCode`), heading scale capped so a `#`/`##` does not fill the screen,
 * accent-colored underlined links.
 *
 * Streaming cost: the library parses inside composition on every recomposition
 * of its scope (verified in the 0.26.0 source — there is no internal memoize),
 * so the mitigation lives here: this wrapper takes only stable params, which
 * keeps it skippable and bounds re-parsing to one parse per text snapshot.
 */
@Composable
internal fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    // Alpha-black so chips/code surfaces read as a subtle darkening of whatever
    // bubble they sit in — a fixed dark color was a hard near-black blob.
    val codeSurface = Color.Black.copy(alpha = 0.3f)
    Markdown(
        content = text,
        modifier = modifier,
        colors = markdownColor(
            text = LocalContentColor.current,
            codeText = LocalContentColor.current,
            linkText = MaterialTheme.colorScheme.primary,
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
        ),
        padding = markdownPadding(indentList = 16.dp),
        // A fresh ExtendedSpans per text node, as the library intends: the class
        // holds per-node draw state, so a shared instance scrambles chip positions
        // across paragraphs.
        extendedSpans = markdownExtendedSpans(
            extendedSpans = { ExtendedSpans(RoundedCornerSpanPainter()) },
        ),
    )
}
