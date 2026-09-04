package io.github.meko123456.ridetogether.android.crash

import io.github.meko123456.ridetogether.android.ui.CrashDetection
import io.github.meko123456.ridetogether.crash.CrashSignal
import io.github.meko123456.ridetogether.crash.CrashState
import io.github.meko123456.ridetogether.crash.MotionSample
import io.github.meko123456.ridetogether.crash.ThresholdCrashDetector
import io.github.meko123456.ridetogether.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant

/**
 * Holds the crash detector for the process, because the two halves of it live in different places:
 * the sensors are read in the location service, and the countdown is cancelled from the screen.
 *
 * Process-level for the same reason as [io.github.meko123456.ridetogether.android.location.RideLocation]
 * — this app has no dependency-injection wiring yet and a service cannot be constructed with
 * arguments. The detector itself is the tested one from `:shared`; nothing here decides anything.
 */
object CrashMonitor : CrashDetection {

    private var detector = ThresholdCrashDetector()

    private val _state = MutableStateFlow(CrashState.IDLE)
    val state: StateFlow<CrashState> = _state

    /** The latest signal, held so the UI can show a countdown that survives recomposition. */
    private val _signal = MutableStateFlow<CrashSignal?>(null)
    override val signal: StateFlow<CrashSignal?> = _signal

    fun feed(sample: MotionSample, location: LatLng?) {
        detector.onMotion(sample, location)?.let { _signal.value = it }
        _state.value = detector.state
    }

    /** The rider said they were fine. */
    override fun cancel(at: Instant) {
        detector.cancel(at)?.let { _signal.value = it }
        _state.value = detector.state
    }

    /** A new ride, or a confirmed crash that has been dealt with. */
    override fun reset() {
        detector = ThresholdCrashDetector()
        _state.value = CrashState.IDLE
        _signal.value = null
    }

    /** Clears a signal the UI has finished acting on, without resetting the detector. */
    override fun consumeSignal() {
        _signal.value = null
    }

    /**
     * Feeds a synthetic impact sequence so the countdown and its cancel button can be exercised
     * without crashing a motorcycle. Deliberately drives the *real* detector rather than faking a
     * signal, so what the rider sees is what a genuine impact would produce — including the fact
     * that nothing happens at all unless the arming conditions were met first.
     */
    override fun simulateImpact(now: Instant) {
        // Riding, so the detector arms.
        feed(MotionSample(now, accelerationMps2 = 2.0, tiltDegrees = 10.0, speedMps = 20.0), null)
        // The impact.
        feed(MotionSample(now + kotlin.time.Duration.parse("1s"), 60.0, 12.0, 18.0), null)
        // On its side and not moving, for long enough to confirm.
        var at = now + kotlin.time.Duration.parse("2s")
        repeat(10) {
            feed(MotionSample(at, accelerationMps2 = 0.5, tiltDegrees = 95.0, speedMps = 0.0), null)
            at += kotlin.time.Duration.parse("1s")
        }
    }
}
