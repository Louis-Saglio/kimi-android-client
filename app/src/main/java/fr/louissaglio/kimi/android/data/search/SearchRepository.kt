package fr.louissaglio.kimi.android.data.search

import fr.louissaglio.kimi.android.data.settings.ServerSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin public facade of the search data layer (ticket 146): delegates the REST verb
 * to [SearchApi]. This is the error boundary: failures come back as [Result.failure]
 * of [fr.louissaglio.kimi.android.data.DataError].
 */
@Singleton
class SearchRepository @Inject constructor(
    private val searchApi: SearchApi,
) {

    /**
     * Runs one page of the global message search (`POST /api/v1/search`). [pageToken]
     * is the previous page's opaque continuation token, passed back verbatim; null
     * fetches the first page.
     */
    suspend fun search(settings: ServerSettings, query: String, pageToken: String? = null): Result<SearchPage> =
        searchApi.search(settings, query, pageToken)
}
