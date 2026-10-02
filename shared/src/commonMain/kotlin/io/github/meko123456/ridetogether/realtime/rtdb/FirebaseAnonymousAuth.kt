package io.github.meko123456.ridetogether.realtime.rtdb

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where Firebase Auth's REST endpoints are: Google's servers, or the Auth emulator. */
data class FirebaseAuthEndpoints(val identityToolkit: String, val secureToken: String) {
    companion object {
        val Production = FirebaseAuthEndpoints(
            identityToolkit = "https://identitytoolkit.googleapis.com",
            secureToken = "https://securetoken.googleapis.com",
        )

        /** The Auth emulator, which serves both APIs from one address under their own host names. */
        fun emulator(host: String) = FirebaseAuthEndpoints(
            identityToolkit = "http://$host/identitytoolkit.googleapis.com",
            secureToken = "http://$host/securetoken.googleapis.com",
        )
    }
}

/**
 * What survives the app being closed: who the rider is, and how to prove it again. Not the ID token,
 * which lasts an hour and is cheaper to fetch than to keep.
 */
data class AuthSession(val uid: String, val refreshToken: String)

/** Where [AuthSession] is kept between launches. Each app provides one over its own storage. */
interface AuthSessionStore {
    suspend fun load(): AuthSession?
    suspend fun save(session: AuthSession)
}

/**
 * The rider's identity, from Firebase Auth's anonymous sign-in over REST, as the credential every
 * database request carries.
 *
 * Anonymous because RideTogether asks for a name and nothing else, and a ride needs a stable id per
 * phone more than it needs an account. Stable is the part that matters: the uid is who leads a
 * room, who is a member, whose position is whose. So it is made **once per install**. The refresh
 * token is kept in [store], and every later launch refreshes rather than signing up again; signing
 * up again would make the rider a stranger to the ride they were on. Only a refresh the server
 * refuses (the session was revoked, or the account deleted) starts a new identity.
 */
class FirebaseAnonymousAuth(
    private val apiKey: String,
    private val http: HttpClient,
    private val store: AuthSessionStore,
    private val endpoints: FirebaseAuthEndpoints = FirebaseAuthEndpoints.Production,
    private val clock: Clock = Clock.System,
) : RtdbCredentials {

    private class Token(val uid: String, val idToken: String, val expiresAt: Instant)

    private val lock = Mutex()
    private var token: Token? = null

    /** The rider's uid, signing in first if this install never has. Null when it cannot be reached. */
    suspend fun uid(): String? = lock.withLock { current()?.uid }

    /**
     * The uid this install last signed in as, without asking anyone. Null only if it never has.
     *
     * For a launch with no connection: the rider is still who they were, and their app should
     * still open as them, even though nothing they send can be proved until the network is back.
     * [uid] is the one to prefer when there is a connection, because only it notices an identity
     * the server has since refused.
     */
    suspend fun knownUid(): String? = lock.withLock { token?.uid ?: store.load()?.uid }

    override suspend fun idToken(): String? = lock.withLock { current()?.idToken }

    private suspend fun current(): Token? {
        // A minute of margin, so a token is never sent with seconds left and refused on arrival.
        token?.takeIf { it.expiresAt - clock.now() > 60.seconds }?.let { return it }
        val saved = store.load()
        val fresh = when (val refreshed = saved?.let { refresh(it) }) {
            is Outcome.Signed -> refreshed.token
            Outcome.Unreachable -> return null
            Outcome.Refused, null -> when (val signedUp = signUp()) {
                is Outcome.Signed -> signedUp.token
                Outcome.Unreachable, Outcome.Refused -> return null
            }
        }
        token = fresh
        return fresh
    }

    private sealed interface Outcome {
        class Signed(val token: Token) : Outcome
        data object Refused : Outcome
        data object Unreachable : Outcome
    }

    private suspend fun signUp(): Outcome {
        val body = post(
            url = "${endpoints.identityToolkit}/v1/accounts:signUp",
            content = TextContent("""{"returnSecureToken":true}""", ContentType.Application.Json),
        ) ?: return Outcome.Unreachable
        val reply = body.getOrElse { return Outcome.Refused }
        return signedIn(reply.string("localId"), reply.string("idToken"), reply.string("refreshToken"), reply.string("expiresIn"))
    }

    private suspend fun refresh(session: AuthSession): Outcome {
        val form = "grant_type=refresh_token&refresh_token=${session.refreshToken.encodeURLParameter()}"
        val body = post(
            url = "${endpoints.secureToken}/v1/token",
            content = TextContent(form, ContentType.Application.FormUrlEncoded),
        ) ?: return Outcome.Unreachable
        val reply = body.getOrElse { return Outcome.Refused }
        return signedIn(reply.string("user_id"), reply.string("id_token"), reply.string("refresh_token"), reply.string("expires_in"))
    }

    private suspend fun signedIn(uid: String?, idToken: String?, refreshToken: String?, expiresIn: String?): Outcome {
        if (uid == null || idToken == null || refreshToken == null) return Outcome.Refused
        store.save(AuthSession(uid, refreshToken))
        val lifetime = expiresIn?.toLongOrNull()?.seconds ?: 3600.seconds
        return Outcome.Signed(Token(uid, idToken, clock.now() + lifetime))
    }

    /** The reply as JSON: null when nothing answered, a failed Result when something said no. */
    private suspend fun post(url: String, content: TextContent): Result<JsonObject>? {
        val response = try {
            http.post(url) {
                parameter("key", apiKey)
                setBody(content)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (!response.status.isSuccess()) return Result.failure(IllegalStateException("${response.status}"))
        return runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}
