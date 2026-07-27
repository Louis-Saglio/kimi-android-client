package fr.louissaglio.kimi.android.data.network

import fr.louissaglio.kimi.android.data.DataError
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class EnvelopeTest {

    @Test
    fun `toResult maps a non-zero code to Api with the server message`() {
        assertEquals(
            Result.failure<JsonObject>(DataError.Api(50001, "boom")),
            Envelope<JsonObject>(code = 50001, msg = "boom", data = JsonObject(emptyMap())).toResult(),
        )
    }

    @Test
    fun `toResult maps a missing data payload to Api missing data`() {
        assertEquals(
            Result.failure<JsonObject>(DataError.Api(0, "missing data")),
            Envelope<JsonObject>(code = 0, msg = "", data = null).toResult(),
        )
    }

    @Test
    fun `toResult unwraps the data payload on success`() {
        val data = JsonObject(emptyMap())

        assertEquals(Result.success(data), Envelope(code = 0, msg = "", data = data).toResult())
    }

    @Test
    fun `toAckResult maps a non-zero code to Api and ignores the payload`() {
        assertEquals(
            Result.failure<Unit>(DataError.Api(40909, "conflict")),
            Envelope<JsonObject>(code = 40909, msg = "conflict", data = null).toAckResult(),
        )
        assertEquals(
            Result.success(Unit),
            Envelope<JsonObject>(code = 0, msg = "", data = null).toAckResult(),
        )
    }
}
