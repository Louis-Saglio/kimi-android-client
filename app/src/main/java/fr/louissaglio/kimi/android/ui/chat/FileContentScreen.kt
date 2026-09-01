package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.data.chat.ReadOutputs
import fr.louissaglio.kimi.android.data.chat.ToolPayload
import fr.louissaglio.kimi.android.ui.common.EmptyState

/**
 * The Read/Write file viewer (ticket 120): one screen for both payloads, fed by the
 * [ToolContentStore] entry staged at tap time — a static snapshot that never
 * live-updates, so there is no ViewModel (the destination consumes the entry once
 * into a `remember`). A Write shows the call's `content` verbatim; a Read shows the
 * call's OUTPUT verbatim — the wire extract already carries `line-number⇥content`
 * prefixes — with a trailing `<system>…</system>` status block lifted into a caption
 * chip when it parses cleanly ([ReadOutputs]). A Read tapped while the call still
 * runs (blank output) shows the running placeholder instead of content. A store miss
 * (process death with this screen on top) renders the missing-content state; back is
 * the way out — the EditDiffScreen recovery, verbatim.
 */
@Composable
fun FileContentRoute(
    toolCallId: String,
    entry: ToolContentStore.Entry?,
    onNavigateBack: () -> Unit,
) {
    when (val payload = entry?.payload) {
        is ToolPayload.Write ->
            FileContentScreen(payload.path, payload.content, onNavigateBack = onNavigateBack)
        is ToolPayload.Read ->
            if (entry.output.isBlank()) {
                FileContentScaffold(payload.path.substringAfterLast('/'), payload.path, onNavigateBack) {
                    EmptyState("Call still running — no content yet")
                }
            } else {
                val lifted = ReadOutputs.of(entry.output)
                FileContentScreen(payload.path, lifted.content, lifted.caption, onNavigateBack)
            }
        // A non-file payload staged under the id is equally a miss (the
        // EditDiffViewModel rule).
        else -> FileContentScaffold(toolCallId, path = null, onNavigateBack = onNavigateBack) {
            MissingContentState(onNavigateBack)
        }
    }
}

/** The shared body (ticket 120's note — parameterized by the caller): the [path]'s
 * basename and full path in the top bar, the [content] verbatim monospace in a
 * vertically scrollable, selectable block, and the optional [caption] (a Read
 * output's lifted status block) as a chip above it. The scroll position is the
 * screen's only UI state (saveable via rememberScrollState). */
@Composable
fun FileContentScreen(
    path: String,
    content: String,
    caption: String? = null,
    onNavigateBack: () -> Unit,
) {
    FileContentScaffold(path.substringAfterLast('/'), path, onNavigateBack) {
        // Body width capped and centered on large screens (the JobDetailScreen recipe).
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (caption != null) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            text = caption,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                text = content,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The screen's shell: basename [title], the full [path] as supporting line (absent
 * in the missing-content state, which has no path), and the back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileContentScaffold(
    title: String,
    path: String?,
    onNavigateBack: () -> Unit,
    body: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (path != null) {
                            Text(
                                text = path,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            body()
        }
    }
}

/** The store-miss state (process death with this screen on top): the Edit diff
 * screen's error-state shape, with Back as the only action — there is nothing to
 * retry, re-tapping the chat row re-stages the entry. */
@Composable
private fun MissingContentState(onNavigateBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "This file content is no longer available — reopen it from the chat.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onNavigateBack) {
            Text("Back")
        }
    }
}
