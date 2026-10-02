package io.github.meko123456.ridetogether.realtime.rtdb

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

internal actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            // OkHttp gives up on a response that has said nothing for 10 seconds. A room's stream
            // says nothing between changes but a keep-alive every 30 (timed against the emulator),
            // so the default would drop and reopen every quiet stream all ride long. A minute
            // still notices a dead one.
            readTimeout(60, TimeUnit.SECONDS)
        }
    }
}
