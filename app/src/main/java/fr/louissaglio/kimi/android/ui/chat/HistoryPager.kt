package fr.louissaglio.kimi.android.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The paged-history loader shared by the main chat and the sub-agent page (ticket
 * 074 — one implementation replacing the ChatViewModel/SubagentViewModel twins,
 * whose drift produced tickets 061 and 062). Owns the two fetch jobs and their
 * guards; everything else stays with the owner and arrives as callbacks (the 072
 * collaborator contract: a logic holder, NOT a second state source):
 *
 * - the fetch (message history vs transcript — the parameterized backing, 046);
 * - the two merge paths, each flushing its own DeltaCoalescer before a replace
 *   (the 062 invariant lives in those callbacks, not here);
 * - the cursor and the hasMore flag (ViewModel state for the chat, the
 *   SubagentStreamStore entry for a sub-agent — ownership unchanged);
 * - the loading-more mirror and the newest-fetch error presentation.
 *
 * [refresh] fetches the NEWEST page, latest-wins: a stale response must not
 * overwrite a newer one. It also cancels an in-flight older-page load first
 * (ticket 061): a page fetched against the stale cursor would land on the
 * refetch's fresh page with a hole in between — contiguous with the OLD
 * committed list, not the fresh one.
 *
 * [loadOlder] fetches the next older page against the cursor and prepends it.
 * Its guards are the same trap from the other side: no-op while an older-page
 * load is in flight, while a newest-page refetch is in flight, once the owner
 * reported no more history, or before any cursor exists. An older-page failure
 * surfaces nothing — the loaded history stays visible and the next trigger
 * retries.
 *
 * All methods are Main-confined (called from the owning ViewModel's methods, the
 * jobs launch on its scope), keeping the job pair single-threaded.
 */
internal class HistoryPager<P>(
    private val scope: () -> CoroutineScope,
    /** Fetches one page; a null cursor fetches the newest page. */
    private val fetch: suspend (beforeId: String?) -> Result<P>,
    /** Runs inside the newest-page job before the fetch (the owner's loading state). */
    private val onNewestStart: () -> Unit,
    /** Merges a fetched NEWEST page: flush, replace (per [keepLive]), cursor/hasMore, publish. */
    private val applyNewest: (page: P, keepLive: Boolean) -> Unit,
    /** Merges a fetched OLDER page: prepend (deduped), cursor/hasMore, publish. */
    private val applyOlder: (page: P) -> Unit,
    /** Reports a failed NEWEST fetch — full-screen error vs snackbar is the owner's call. */
    private val onNewestError: suspend (Throwable) -> Unit,
    /** The owner's end-of-history flag, for the [loadOlder] guard. */
    private val hasMore: () -> Boolean,
    /** The owner's cursor of the oldest loaded entry; null before the first page lands. */
    private val cursor: () -> String?,
    /** Mirrors the older-page spinner into the owner's state. */
    private val setLoadingMore: (Boolean) -> Unit,
) {

    /** Newest-page loads (initial + refreshes); latest wins. */
    private var newestJob: Job? = null

    /** In-flight guard of the older-page load (ticket 010's pattern): the Job, not a
     * state flag. */
    private var olderJob: Job? = null

    /** Newest-page (re)fetch, latest-wins; [keepLive] carries the owner's live-tail
     * decision into [applyNewest] (a finished turn drops its live rows, a running
     * one keeps them). */
    fun refresh(keepLive: Boolean) {
        newestJob?.cancel()
        // The newest-page reset cancels the older-page load (ticket 061).
        olderJob?.cancel()
        setLoadingMore(false)
        newestJob = scope().launch {
            onNewestStart()
            fetch(null)
                .onSuccess { applyNewest(it, keepLive) }
                .onFailure { onNewestError(it) }
        }
    }

    /** Scroll-to-top trigger: the guarded older-page fetch + prepend. */
    fun loadOlder() {
        if (olderJob?.isActive == true) return
        if (newestJob?.isActive == true) return
        if (!hasMore()) return
        val beforeId = cursor() ?: return
        olderJob = scope().launch {
            setLoadingMore(true)
            try {
                fetch(beforeId).onSuccess { applyOlder(it) }
            } finally {
                setLoadingMore(false)
            }
        }
    }
}
