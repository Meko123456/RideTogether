package io.github.meko123456.ridetogether.android.location

import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.android.ui.OwnLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * Where the service puts fixes and where the UI reads them.
 *
 * A process-level object rather than an injected repository, deliberately and temporarily: there
 * is no dependency-injection wiring in this app yet, and a service cannot be constructed with
 * arguments. When the realtime layer arrives (#10) this becomes the thing that publishes to
 * Firebase instead, and the UI reads the room rather than the phone.
 */
object RideLocation : OwnLocation {

    private val _own = MutableStateFlow<RiderSample?>(null)

    /** The latest fix for this phone, or null before there is one. */
    override val own: StateFlow<RiderSample?> = _own

    private val _interval = MutableStateFlow<Duration?>(null)

    /** The interval the service is currently asking the provider for, for the diagnostics line. */
    val interval: StateFlow<Duration?> = _interval

    private val _running = MutableStateFlow(false)

    /** Whether the location service is actually collecting. The privacy claim, observable. */
    val running: StateFlow<Boolean> = _running

    fun publish(sample: RiderSample) {
        _own.value = sample
    }

    fun setInterval(value: Duration?) {
        _interval.value = value
    }

    fun setRunning(value: Boolean) {
        _running.value = value
        if (!value) _own.value = null
    }
}
