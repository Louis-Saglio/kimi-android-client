package fr.louissaglio.kimi.android.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val code: Int,
    val msg: String,
    val data: T? = null
)
