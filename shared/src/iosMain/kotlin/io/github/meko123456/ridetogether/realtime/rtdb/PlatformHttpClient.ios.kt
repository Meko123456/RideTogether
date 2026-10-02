package io.github.meko123456.ridetogether.realtime.rtdb

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

// URLSession waits 60 seconds for more of a response by default, which already outlasts the
// stream's keep-alive, so the engine needs nothing set.
internal actual fun platformHttpClient(): HttpClient = HttpClient(Darwin)
