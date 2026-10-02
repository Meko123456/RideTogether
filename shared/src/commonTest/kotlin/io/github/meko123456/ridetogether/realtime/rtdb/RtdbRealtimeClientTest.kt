package io.github.meko123456.ridetogether.realtime.rtdb

import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.model.JoinCode
import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.realtime.RealtimeError
import io.github.meko123456.ridetogether.realtime.RealtimeResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant

/**
 * What the client makes of each kind of answer, against a scripted server. Behaviour that depends
 * on the real rules is tested against the database emulator instead (RtdbEmulatorTest).
 */
class RtdbRealtimeClientTest {

    private val t0 = Instant.fromEpochMilliseconds(1_756_000_000_000)
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): RtdbRealtimeClient {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return RtdbRealtimeClient(
            selfId = "me",
            selfName = "Merab",
            database = RtdbDatabase("http://127.0.0.1:9110", namespace = "rides"),
            credentials = { "token-for-me" },
            http = HttpClient(engine),
        )
    }

    @Test
    fun `requests go to the path as json with the namespace and the credential`() = runTest {
        val c = client { respond("null") }
        c.findRoom(JoinCode("7KQ2WX"))
        val url = requests.single().url
        assertEquals("/codes/7KQ2WX.json", url.encodedPath)
        assertEquals("rides", url.parameters["ns"])
        assertEquals("token-for-me", url.parameters["auth"])
    }

    @Test
    fun `a code nobody holds is no room rather than an error`() = runTest {
        assertEquals(RealtimeResult.Success(null), client { respond("null") }.findRoom(JoinCode("7KQ2WX")))
    }

    @Test
    fun `a taken code is reported before anything is written`() = runTest {
        val c = client { respond("\"someone-elses-room\"") }
        assertEquals(RealtimeError.CODE_TAKEN, c.createRoom("Sunday run", JoinCode("7KQ2WX"), t0).errorOrNull)
        assertEquals(listOf(HttpMethod.Get), requests.map { it.method })
    }

    @Test
    fun `losing the race for a code reads as taken too`() = runTest {
        // Free when checked, refused when claimed: the rules make codes write-once.
        val c = client { request ->
            if (request.method == HttpMethod.Get) respond("null") else respond("{\"error\":\"Permission denied\"}", HttpStatusCode.Unauthorized)
        }
        assertEquals(RealtimeError.CODE_TAKEN, c.createRoom("Sunday run", JoinCode("7KQ2WX"), t0).errorOrNull)
    }

    @Test
    fun `a refusal from the rules is not permitted and a gateway failure is offline`() = runTest {
        assertEquals(
            RealtimeError.NOT_PERMITTED,
            client { respond("{\"error\":\"Permission denied\"}", HttpStatusCode.Unauthorized) }.findRoom(JoinCode("7KQ2WX")).errorOrNull,
        )
        assertEquals(
            RealtimeError.OFFLINE,
            client { respond("", HttpStatusCode.ServiceUnavailable) }.findRoom(JoinCode("7KQ2WX")).errorOrNull,
        )
    }

    @Test
    fun `no answer at all is offline and the client says it is not connected`() = runTest {
        val c = client { throw IllegalStateException("connection refused") }
        assertEquals(RealtimeError.OFFLINE, c.findRoom(JoinCode("7KQ2WX")).errorOrNull)
        assertEquals(false, c.connected.first())
    }

    @Test
    fun `nothing is sent in another rider's name`() = runTest {
        val c = client { respond("null") }
        val someoneElse = RiderSample("not-me", LatLng(41.7, 44.8), 10f, t0)
        assertEquals(RealtimeError.NOT_PERMITTED, c.publishPosition("room-1", someoneElse).errorOrNull)
        assertEquals(RealtimeError.NOT_PERMITTED, c.publishEvent("room-1", RideEvent.Joined(t0, "not-me")).errorOrNull)
        assertTrue(requests.isEmpty(), "refused before any request: ${requests.map { it.url }}")
    }

    @Test
    fun `a refused position in a room that is gone says the room is gone`() = runTest {
        val c = client { request ->
            if (request.method == HttpMethod.Put) respond("{\"error\":\"Permission denied\"}", HttpStatusCode.Unauthorized) else respond("null")
        }
        val mine = RiderSample("me", LatLng(41.7, 44.8), 10f, t0)
        assertEquals(RealtimeError.ROOM_GONE, c.publishPosition("room-1", mine).errorOrNull)
    }
}
