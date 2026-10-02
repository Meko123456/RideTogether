package io.github.meko123456.ridetogether.realtime.rtdb

import io.github.meko123456.ridetogether.realtime.RealtimeClient
import io.ktor.client.HttpClient

/**
 * What an app needs to know to ride through a Firebase project.
 *
 * Each app reads these from its own build settings, never from a committed file: they identify a
 * project, and a project is whoever deploys the app's to give, not the repo's.
 */
data class FirebaseSettings(
    /** The Realtime Database: `https://<name>.<region>.firebasedatabase.app`, or an emulator's `http://host:port`. */
    val databaseUrl: String,
    /** The project's Web API key, which names the project to Firebase Auth. */
    val apiKey: String,
    /** The emulator's database name, sent as `?ns=`. Null for a real project, whose host already says. */
    val databaseNamespace: String? = null,
    /** `host:port` of the Auth emulator. Null for the real Firebase Auth. */
    val authEmulatorHost: String? = null,
)

/**
 * A Firebase project, as the apps see it: a rider id and a [RealtimeClient], and nothing about how
 * the bytes travel. HTTP, the engine and the token plumbing stay inside `shared`, so neither app
 * depends on Ktor, and both get the same client.
 */
class FirebaseBackend(settings: FirebaseSettings, store: AuthSessionStore) {

    private val http = platformHttpClient()
    private val database = RtdbDatabase(settings.databaseUrl, settings.databaseNamespace)
    private val auth = FirebaseAnonymousAuth(
        apiKey = settings.apiKey,
        http = http,
        store = store,
        endpoints = settings.authEmulatorHost?.let(FirebaseAuthEndpoints::emulator)
            ?: FirebaseAuthEndpoints.Production,
    )

    /**
     * Who this install is: signed in now when that can be done, as it was last time when it cannot.
     * Null only for an install that has never signed in and cannot now, which makes the first
     * launch the one that needs a connection.
     */
    suspend fun riderId(): String? = auth.uid() ?: auth.knownUid()

    /** A client writing as [riderId], under [name], which is what the other riders will see. */
    fun client(riderId: String, name: String): RealtimeClient = RtdbRealtimeClient(
        selfId = riderId,
        selfName = name,
        database = database,
        credentials = auth,
        http = http,
    )
}

/**
 * The platform's own HTTP engine, named rather than discovered: Ktor finds an engine through a
 * service loader by default, which a minified release can lose.
 */
internal expect fun platformHttpClient(): HttpClient
