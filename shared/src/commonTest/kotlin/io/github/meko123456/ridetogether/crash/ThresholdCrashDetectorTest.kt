package io.github.meko123456.ridetogether.crash

import io.github.meko123456.ridetogether.model.LatLng
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Synthetic sensor traces. Every test below is a scenario a real rider produces on an ordinary
 * afternoon — the point of the detector is telling them apart, so the tests are named after the
 * situation rather than the branch.
 */
class ThresholdCrashDetectorTest {

    private val t0 = Instant.parse("2026-09-01T14:00:00Z")
    private val here = LatLng(41.7151, 44.8271)
    private val config = CrashConfig()

    private fun sample(
        at: Instant,
        accel: Double = 2.0,
        tilt: Double = 10.0,
        speed: Double? = 20.0,
    ) = MotionSample(at = at, accelerationMps2 = accel, tiltDegrees = tilt, speedMps = speed)

    /** Rides normally for a while so the detector is armed and the tilt baseline has settled. */
    private fun ride(detector: CrashDetector, from: Instant = t0, seconds: Int = 30): Instant {
        var at = from
        repeat(seconds) {
            detector.onMotion(sample(at), here)
            at += 1.seconds
        }
        return at
    }

    /** The full sequence: impact, then the bike on its side, then nothing moving. */
    private fun crash(detector: CrashDetector, from: Instant): Pair<Instant, List<CrashSignal>> {
        val signals = mutableListOf<CrashSignal>()
        var at = from
        detector.onMotion(sample(at, accel = 55.0, speed = 18.0), here)?.let(signals::add)
        at += 1.seconds
        repeat(8) {
            detector.onMotion(sample(at, accel = 1.0, tilt = 95.0, speed = 0.0), here)?.let(signals::add)
            at += 1.seconds
        }
        return at to signals
    }

    @Test
    fun `a crash is an impact then the bike on its side then stillness`() {
        val detector = ThresholdCrashDetector(config)
        val after = ride(detector)
        val (at, signals) = crash(detector, after)

        val started = signals.filterIsInstance<CrashSignal.CountdownStarted>()
        assertEquals(1, started.size, "exactly one countdown: $signals")
        assertEquals(CrashState.COUNTING_DOWN, detector.state)

        // Nobody cancels.
        var now = at
        var confirmed: CrashSignal.CrashConfirmed? = null
        repeat(40) {
            val signal = detector.onMotion(sample(now, accel = 0.5, tilt = 95.0, speed = 0.0), here)
            if (signal is CrashSignal.CrashConfirmed) confirmed = signal
            now += 1.seconds
        }
        val crash = confirmed
        assertTrue(crash != null, "the countdown must expire into a confirmed crash")
        assertEquals(here, crash.location, "the group needs somewhere to ride back to")
        assertEquals(CrashState.CRASH_REPORTED, detector.state)
    }

    @Test
    fun `braking as hard as the tyres allow is not a crash`() {
        // ~1 g, which is a genuinely hard stop on a good road, and the bike stays upright.
        val detector = ThresholdCrashDetector(config)
        var at = ride(detector)
        val signals = mutableListOf<CrashSignal>()
        repeat(4) {
            detector.onMotion(sample(at, accel = 10.0, tilt = 12.0, speed = 8.0), here)?.let(signals::add)
            at += 1.seconds
        }
        repeat(10) {
            detector.onMotion(sample(at, accel = 1.0, tilt = 12.0, speed = 0.0), here)?.let(signals::add)
            at += 1.seconds
        }
        assertTrue(signals.isEmpty(), "hard braking to a stop at a junction: $signals")
        assertEquals(CrashState.IDLE, detector.state)
    }

    @Test
    fun `a pothole is an impact the rider rides straight out of`() {
        val detector = ThresholdCrashDetector(config)
        var at = ride(detector)
        val signals = mutableListOf<CrashSignal>()
        detector.onMotion(sample(at, accel = 48.0, speed = 22.0), here)?.let(signals::add)
        at += 1.seconds
        assertEquals(CrashState.IMPACT_SUSPECTED, detector.state, "the impact itself is real enough")
        repeat(10) {
            detector.onMotion(sample(at, accel = 3.0, tilt = 11.0, speed = 21.0), here)?.let(signals::add)
            at += 1.seconds
        }
        assertTrue(signals.isEmpty(), "still riding means fine: $signals")
        assertEquals(CrashState.IDLE, detector.state)
    }

    @Test
    fun `dropping the phone in the car park is not a crash`() {
        // Never above arming speed, so the detector was never armed. This is the false positive
        // most likely to happen in practice -- a phone hitting tarmac reads far harder than a
        // motorcycle impact.
        val detector = ThresholdCrashDetector(config)
        var at = t0
        val signals = mutableListOf<CrashSignal>()
        repeat(5) {
            detector.onMotion(sample(at, accel = 1.0, tilt = 5.0, speed = 0.5), here)?.let(signals::add)
            at += 1.seconds
        }
        detector.onMotion(sample(at, accel = 120.0, tilt = 88.0, speed = 0.0), here)?.let(signals::add)
        at += 1.seconds
        repeat(10) {
            detector.onMotion(sample(at, accel = 1.0, tilt = 88.0, speed = 0.0), here)?.let(signals::add)
            at += 1.seconds
        }
        assertTrue(signals.isEmpty(), "a dropped phone is not a crashed rider: $signals")
    }

    @Test
    fun `taking the phone out of a pocket at a fuel stop is not a crash`() {
        // Tilt and stillness, but no impact opened the window -- the same signals in the wrong
        // order.
        val detector = ThresholdCrashDetector(config)
        var at = ride(detector)
        val signals = mutableListOf<CrashSignal>()
        repeat(15) {
            detector.onMotion(sample(at, accel = 4.0, tilt = 100.0, speed = 0.0), here)?.let(signals::add)
            at += 1.seconds
        }
        assertTrue(signals.isEmpty(), "order matters - only an impact opens the window: $signals")
    }

    @Test
    fun `an impact with no tilt change never starts a countdown`() {
        val detector = ThresholdCrashDetector(config)
        var at = ride(detector)
        val signals = mutableListOf<CrashSignal>()
        detector.onMotion(sample(at, accel = 60.0, speed = 20.0), here)?.let(signals::add)
        at += 1.seconds
        // Past the 12 s confirm window, so the state assertion below is about the window
        // actually closing rather than about it not having opened yet.
        repeat(15) {
            detector.onMotion(sample(at, accel = 1.0, tilt = 12.0, speed = 0.0), here)?.let(signals::add)
            at += 1.seconds
        }
        assertTrue(signals.isEmpty(), "upright and stopped is a red light: $signals")
        assertEquals(CrashState.IDLE, detector.state, "the window must close on its own")
    }

    @Test
    fun `the rider can cancel the countdown`() {
        val detector = ThresholdCrashDetector(config)
        val after = ride(detector)
        val (at, _) = crash(detector, after)

        val cancelled = detector.cancel(at)
        assertTrue(cancelled is CrashSignal.CountdownCancelled)
        assertEquals(CancelReason.RIDER, cancelled.reason)
        assertEquals(CrashState.IDLE, detector.state)

        // And nothing arrives afterwards.
        var now = at + 1.seconds
        val later = mutableListOf<CrashSignal>()
        repeat(60) {
            detector.onMotion(sample(now, accel = 1.0, tilt = 95.0, speed = 0.0), here)?.let(later::add)
            now += 1.seconds
        }
        assertTrue(
            later.filterIsInstance<CrashSignal.CrashConfirmed>().isEmpty(),
            "a cancelled countdown stays cancelled: $later",
        )
    }

    @Test
    fun `riding away cancels the countdown by itself`() {
        val detector = ThresholdCrashDetector(config)
        val after = ride(detector)
        val (at, _) = crash(detector, after)

        val signal = detector.onMotion(sample(at, accel = 3.0, tilt = 15.0, speed = 15.0), here)
        assertTrue(signal is CrashSignal.CountdownCancelled)
        assertEquals(CancelReason.RIDING_AGAIN, signal.reason, "nobody rides at 54 km/h unconscious")
        assertEquals(CrashState.IDLE, detector.state)
    }

    @Test
    fun `a confirmed crash is not repeated and not withdrawn`() {
        val detector = ThresholdCrashDetector(config)
        val after = ride(detector)
        var (at, _) = crash(detector, after)
        var confirmations = 0
        repeat(90) {
            val signal = detector.onMotion(sample(at, accel = 1.0, tilt = 95.0, speed = 0.0), here)
            if (signal is CrashSignal.CrashConfirmed) confirmations++
            at += 1.seconds
        }
        assertEquals(1, confirmations, "announced once, then sticky")
        assertNull(detector.cancel(at), "cancelling after the fact is not a thing")
        assertEquals(CrashState.CRASH_REPORTED, detector.state)
    }

    @Test
    fun `stillness with no fix at all cannot confirm a crash`() {
        // No speed means we do not know whether the bike is moving. The alert engine takes the
        // same position: absent evidence is not evidence.
        val detector = ThresholdCrashDetector(config)
        var at = ride(detector)
        val signals = mutableListOf<CrashSignal>()
        detector.onMotion(sample(at, accel = 60.0, speed = 20.0), here)?.let(signals::add)
        at += 1.seconds
        repeat(11) {
            detector.onMotion(sample(at, accel = 1.0, tilt = 95.0, speed = null), here)?.let(signals::add)
            at += 1.seconds
        }
        assertTrue(signals.isEmpty(), "no fix means no confirmation: $signals")
    }

    @Test
    fun `resetting rearms the detector from scratch`() {
        val detector = ThresholdCrashDetector(config)
        val after = ride(detector)
        crash(detector, after)
        detector.reset()
        assertEquals(CrashState.IDLE, detector.state)

        // Straight after a reset the detector is unarmed: an impact with no riding behind it is
        // ignored until the rider is moving again.
        val signals = mutableListOf<CrashSignal>()
        var at = after + 60.seconds
        detector.onMotion(sample(at, accel = 90.0, tilt = 95.0, speed = 0.0), here)?.let(signals::add)
        at += 1.seconds
        repeat(10) {
            detector.onMotion(sample(at, accel = 1.0, tilt = 95.0, speed = 0.0), here)?.let(signals::add)
            at += 1.seconds
        }
        assertTrue(signals.isEmpty(), "reset means unarmed: $signals")
    }

    @Test
    fun `detection can be switched off entirely`() {
        val detector: CrashDetector = DisabledCrashDetector
        val after = ride(detector)
        val (_, signals) = crash(detector, after)
        assertTrue(signals.isEmpty(), "off means off")
        assertEquals(CrashState.IDLE, detector.state)
    }
}
