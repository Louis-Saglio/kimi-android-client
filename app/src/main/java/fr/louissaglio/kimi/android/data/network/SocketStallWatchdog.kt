package fr.louissaglio.kimi.android.data.network

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout

/**
 * Stalled-socket watchdog (ticket 152), shared by both WebSockets (the chat's
 * SessionSocket, the session list's). The 0.38.0 drift log recorded a live anomaly:
 * a subscribed socket can silently stall — no close frame, no events, not even the
 * server's heartbeat pings — while REST shows the run completing, and with the read
 * timeout disabled such a socket stays "connected" forever. The server pings every
 * `heartbeat_ms` and closes after ~2 cycles of silence, so inbound silence of 3×
 * `heartbeat_ms` is unambiguously a stall: the socket is treated as dropped and the
 * owning reconnect loop takes over (backoff, cursor resume, REST resync — all
 * existing machinery).
 *
 * One instance per connection: a reconnect constructs a fresh one, so the timer and
 * the heartbeat value (each connection gets its own server_hello) reset across
 * reconnects by construction.
 */
internal class SocketStallWatchdog(private val tag: String) {

    private var heartbeatMs = DEFAULT_HEARTBEAT_MS
    private var armed = false

    /** Arms the watchdog at socket open — never before: the TCP/TLS connect phase is
     * covered by the connect timeout, not by frame-idle detection. */
    fun onOpen() {
        armed = true
    }

    /** Adopts the server_hello's `heartbeat_ms`; a non-positive value keeps the
     * protocol default. */
    fun onServerHello(heartbeatMs: Long) {
        if (heartbeatMs > 0) this.heartbeatMs = heartbeatMs
    }

    /**
     * Receives the next socket signal. The caller's loop re-enters this on every
     * signal, so each inbound frame — a heartbeat ping alone included — starts a
     * fresh full window; only silence past 3× `heartbeat_ms` throws, unwinding the
     * connection as an ordinary failure.
     */
    suspend fun <T> receive(signals: ReceiveChannel<T>): T {
        if (!armed) return signals.receive()
        val timeoutMs = heartbeatMs * STALL_FACTOR
        return try {
            withTimeout(timeoutMs) { signals.receive() }
        } catch (e: TimeoutCancellationException) {
            // Field stalls must be distinguishable from server closes in reports:
            // this close is client-initiated.
            Log.w(tag, "No inbound frame for $timeoutMs ms; treating the socket as dropped")
            throw IOException("stalled socket: no inbound frame for $timeoutMs ms")
        }
    }

    private companion object {
        // docs/server-api.md, Heartbeat: 10000 is the protocol default, in effect
        // until this connection's server_hello advertises its own value.
        const val DEFAULT_HEARTBEAT_MS = 10_000L
        const val STALL_FACTOR = 3L
    }
}
