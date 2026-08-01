package fr.louissaglio.kimi.android.testutil

import mockwebserver3.MockResponse
import okhttp3.WebSocketListener

/** mockwebserver3's WebSocket upgrade response (the legacy artifact's
 * `MockResponse().withWebSocketUpgrade(listener)` idiom, which has no direct
 * equivalent on the immutable `MockResponse`) — ticket 081. */
fun webSocketUpgradeResponse(listener: WebSocketListener): MockResponse =
    MockResponse.Builder().webSocketUpgrade(listener).build()
