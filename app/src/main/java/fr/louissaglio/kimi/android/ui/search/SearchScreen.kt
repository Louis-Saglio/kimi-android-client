package fr.louissaglio.kimi.android.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.louissaglio.kimi.android.data.search.SearchHit
import fr.louissaglio.kimi.android.ui.common.EmptyState
import fr.louissaglio.kimi.android.ui.common.ErrorState
import fr.louissaglio.kimi.android.ui.common.LoadingState
import fr.louissaglio.kimi.android.ui.common.formatTimestamp
import fr.louissaglio.kimi.android.ui.common.icons.DocumentIcon
import fr.louissaglio.kimi.android.ui.common.icons.SparklesIcon
import fr.louissaglio.kimi.android.ui.sessions.LOAD_MORE_THRESHOLD
import fr.louissaglio.kimi.android.ui.sessions.LoadMoreFooter
import fr.louissaglio.kimi.android.ui.theme.warningColor

/**
 * The message search screen (ticket 146; ticket 147's session-scoped variant): a
 * query field in the top bar (submit on the IME search action), the hit list — role
 * icon, session title, snippet, hit time — paging on scroll, and the stale-index
 * banner when the server reports a lagging index. Scoped to one session, the title
 * slot's placeholder and the prompt name it. Tapping a hit opens its session's chat —
 * or, scoped, just returns to the chat below (jump-to-message is out of scope). A
 * server with its search index off gets the dedicated disabled state (ticket 183).
 */
@Composable
fun SearchScreen(
    onNavigateBack: () -> Unit,
    onOpenSession: (SearchHit) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SearchContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onQueryChange = viewModel::onQueryChange,
        onSearchSubmit = viewModel::onSearch,
        onHitClick = onOpenSession,
        onRetry = viewModel::onRetry,
        onEndReached = viewModel::onEndReached,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchContent(
    uiState: SearchViewModel.UiState,
    onNavigateBack: () -> Unit,
    onQueryChange: (String) -> Unit = {},
    onSearchSubmit: () -> Unit = {},
    onHitClick: (SearchHit) -> Unit = {},
    onRetry: () -> Unit = {},
    onEndReached: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = uiState.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp),
                        placeholder = {
                            Text(
                                // Scoped (ticket 147): the title slot names the session.
                                if (uiState.scopeTitle != null) "Search in \"${uiState.scopeTitle}\""
                                else "Search messages",
                            )
                        },
                        // Ticket 183: nothing to type into once the server said search is off.
                        enabled = !uiState.searchDisabled,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.indexStale && !uiState.searchDisabled) {
                StaleIndexBanner()
            }
            when {
                uiState.searchDisabled -> SearchDisabledState()
                uiState.submittedQuery == null ->
                    EmptyState(
                        if (uiState.scopeTitle != null) {
                            "Search messages and replies in \"${uiState.scopeTitle}\""
                        } else {
                            "Search messages, replies and titles across all sessions"
                        },
                    )
                uiState.error != null -> ErrorState(uiState.error, onRetry)
                uiState.results == null -> LoadingState()
                uiState.results.isEmpty() ->
                    EmptyState("No results for \"${uiState.submittedQuery}\"")
                else -> SearchResultList(uiState, onHitClick, onEndReached)
            }
        }
    }
}

/** Ticket 183: the server's global search index is off (the 2.5.0 default). Says so
 * in phone terms — no server config key, and no Retry: it could only fail the same
 * way until the operator turns the index on. */
@Composable
private fun SearchDisabledState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "Search is disabled on this server",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "The server operator can enable the search index.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** docs/server-api.md: `index_state.stale`/`degraded` means the served page comes
 * from a lagging index generation. Slim and non-blocking — the results stay usable. */
@Composable
private fun StaleIndexBanner() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.warningColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "Search index rebuilding — results may be incomplete",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SearchResultList(
    uiState: SearchViewModel.UiState,
    onHitClick: (SearchHit) -> Unit,
    onEndReached: () -> Unit,
) {
    val results = uiState.results.orEmpty()
    val listState = rememberLazyListState()
    // Fires when scrolling within a few rows of the end; the ViewModel no-ops unless
    // another page is actually available, so repeated triggers near the end are cheap.
    val endReached by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisibleIndex >= layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(endReached) { if (endReached) onEndReached() }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        // No item keys: a hit has no stable identity and the list is append/replace only.
        items(results, contentType = { it::class }) { hit ->
            SearchHitRow(hit, onClick = { onHitClick(hit) })
        }
        if (uiState.isLoadingMore || uiState.loadMoreError != null) {
            item(key = "load_more_footer", contentType = "footer") {
                LoadMoreFooter(error = uiState.loadMoreError, onRetry = onEndReached)
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = when (hit.role) {
                    SearchHit.Role.User -> Icons.Filled.Person
                    SearchHit.Role.Assistant -> SparklesIcon
                    SearchHit.Role.Title -> DocumentIcon
                },
                contentDescription = when (hit.role) {
                    SearchHit.Role.User -> "User message"
                    SearchHit.Role.Assistant -> "Assistant reply"
                    SearchHit.Role.Title -> "Session title"
                },
                modifier = Modifier.size(20.dp),
            )
        },
        headlineContent = {
            Text(
                text = hit.sessionTitle ?: hit.sessionId,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (hit.snippet.isBlank()) {
            null
        } else {
            {
                Text(
                    text = hit.snippet,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            Text(
                text = formatTimestamp(hit.time),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
