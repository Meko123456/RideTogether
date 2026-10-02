package io.github.meko123456.ridetogether.realtime.rtdb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class FirebaseAnonymousAuthTest {

    private class TestClock(var now: Instant) : Clock {
        override fun now(): Instant = now
        fun advance(by: Duration) { now += by }
    }

    private class MemoryStore(var session: AuthSession? = null) : AuthSessionStore {
        override suspend fun load(): AuthSession? = session
        override suspend fun save(session: AuthSession) { this.session = session }
    }

    private val clock = TestClock(Instant.fromEpochMilliseconds(1_756_000_000_000))
    private val requests = mutableListOf<HttpRequestData>()

    private fun auth(store: AuthSessionStore, handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        FirebaseAnonymousAuth(
            apiKey = "test-key",
            http = HttpClient(MockEngine { request -> requests += request; handler(request) }),
            store = store,
            clock = clock,
        )

    private val HttpRequestData.isSignUp get() = url.encodedPath.endsWith("accounts:signUp")
    private val HttpRequestData.form get() = (body as TextContent).text

    @Test
    fun `the first token signs up once and keeps the session`() = runTest {
        val store = MemoryStore()
        val auth = auth(store) { respond("""{"localId":"u1","idToken":"t1","refreshToken":"r1","expiresIn":"3600"}""") }
        assertEquals("t1", auth.idToken())
        assertEquals("t1", auth.idToken())
        assertEquals("u1", auth.uid())
        assertEquals(1, requests.size, "one sign-up, then the cached token")
        assertEquals("test-key", requests.single().url.parameters["key"])
        assertEquals(AuthSession("u1", "r1"), store.session)
    }

    @Test
    fun `a token about to expire is refreshed and the rider keeps their uid`() = runTest {
        val store = MemoryStore()
        val auth = auth(store) { request ->
            if (request.isSignUp) {
                respond("""{"localId":"u1","idToken":"t1","refreshToken":"r1","expiresIn":"3600"}""")
            } else {
                respond("""{"user_id":"u1","id_token":"t2","refresh_token":"r2","expires_in":"3600"}""")
            }
        }
        assertEquals("t1", auth.idToken())
        clock.advance(3550.seconds)
        assertEquals("t2", auth.idToken(), "under a minute left is not worth sending")
        assertEquals("grant_type=refresh_token&refresh_token=r1", requests.last().form)
        assertEquals(AuthSession("u1", "r2"), store.session)
    }

    @Test
    fun `a later launch refreshes the kept session instead of signing up again`() = runTest {
        val store = MemoryStore(AuthSession("u1", "r1"))
        val auth = auth(store) { respond("""{"user_id":"u1","id_token":"t9","refresh_token":"r1","expires_in":"3600"}""") }
        assertEquals("u1", auth.uid(), "the same rider as before, not a stranger to the ride")
        assertEquals(listOf(false), requests.map { it.isSignUp })
    }

    @Test
    fun `a refused refresh starts a new identity`() = runTest {
        val store = MemoryStore(AuthSession("u1", "revoked"))
        val auth = auth(store) { request ->
            if (request.isSignUp) {
                respond("""{"localId":"u2","idToken":"t1","refreshToken":"r2","expiresIn":"3600"}""")
            } else {
                respond("""{"error":{"message":"INVALID_REFRESH_TOKEN"}}""", HttpStatusCode.BadRequest)
            }
        }
        assertEquals("u2", auth.uid())
        assertEquals(AuthSession("u2", "r2"), store.session)
    }

    @Test
    fun `no answer at all is no token and the kept session is left alone`() = runTest {
        val store = MemoryStore(AuthSession("u1", "r1"))
        val auth = auth(store) { throw IllegalStateException("no network") }
        assertNull(auth.idToken())
        assertEquals(AuthSession("u1", "r1"), store.session, "a dropped connection must not cost the rider their identity")
    }
}
