package fr.louissaglio.kimi.android.data.network

import kotlinx.serialization.Serializable

/**
 * Generic REST envelope of the Kimi Code server. Note per docs/server-api.md:
 * errors come back as HTTP 200 with a non-zero [code], so [code] must be checked
 * in addition to the HTTP status.
 */
@Serializable
data class Envelope<T>(
    val code: Int = 0,
    val msg: String = "",
    val data: T? = null,
)
