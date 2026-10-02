package io.github.meko123456.ridetogether.android.backend

import io.github.meko123456.ridetogether.android.BuildConfig
import io.github.meko123456.ridetogether.realtime.rtdb.FirebaseSettings

/**
 * Which backend rides go through. The build decides, not the app: the Firebase project the build
 * was given, or, when it was given none, this phone's memory, on which a ride exists only on the
 * phone that made it. See "Running against Firebase" in the README.
 */
object RideBackends {

    /** This build's Firebase project, or null when it has none and rides stay on the phone. */
    val firebase: FirebaseSettings? = BuildConfig.FIREBASE_DATABASE_URL.takeIf(String::isNotBlank)?.let { url ->
        FirebaseSettings(
            databaseUrl = url,
            apiKey = BuildConfig.FIREBASE_API_KEY,
            databaseNamespace = BuildConfig.FIREBASE_DATABASE_NAMESPACE.ifBlank { null },
            authEmulatorHost = BuildConfig.FIREBASE_AUTH_EMULATOR_HOST.ifBlank { null },
        )
    }
}
