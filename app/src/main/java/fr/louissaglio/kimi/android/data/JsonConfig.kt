package fr.louissaglio.kimi.android.data

import kotlinx.serialization.json.Json

val kimiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
}
