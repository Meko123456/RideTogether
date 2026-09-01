package io.github.meko123456.ridetogether.crash

import io.github.meko123456.ridetogether.model.LatLng
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One reading from the phone's motion sensors, paired with whatever the location provider last
 * said about speed.
 *
 * @property accelerationMps2 magnitude of *linear* acceleration (gravity already removed) in
 *   m/s². The platform source is responsible for that subtraction, because every OS does it
 *   differently and none of it belongs in the detector.
 * @property tiltDegrees angle between the phone's current orientation and vertical. Absolute
 *   value is meaningless — a phone in a jacket pocket sits at any angle — so only *changes* in
 *   it are used.
 * @property speedMps ground speed if the location provider has a recent fix, else null.
 */
data class MotionSample(
    val at: Instant,
    val accelerationMps2: Double,
    val tiltDegrees: Double,
    val speedMps: Double?,
)

/**
 * What the detector will tell the app. Deliberately a **separate hierarchy** from
 * `alerts.Alert`: the alert engine cannot construct one of these and the detector cannot
 * construct an `Alert`, so "a crash alarm can never be raised from the separation logic" is
 * enforced by the compiler rather than by a comment.
 */
sealed interface CrashSignal {
    /**
     * A crash looks likely and the rider has [expiresAt] to say otherwise. The app must make
     * this loud and local — it is the whole false-positive escape hatch.
     */
    data class CountdownStarted(val at: Instant, val expiresAt: Instant) : CrashSignal

    /** The rider said they were fine, or rode off under their own power. */
    data class CountdownCancelled(val at: Instant, val reason: CancelReason) : CrashSignal

    /** Nobody cancelled. This is the one the group hears. */
    data class CrashConfirmed(
        val at: Instant,
        val location: LatLng?,
        /** When the impact itself was detected, which is the coordinate worth riding back to. */
        val impactAt: Instant,
    ) : CrashSignal
}

enum class CancelReason {
    /** The rider tapped "I'm fine". */
    RIDER,

    /**
     * The bike is moving at riding speed again. Nobody rides at 18 km/h unconscious, so this is
     * a safe automatic dismissal — and it covers the common false positive of a dropped phone
     * being picked up and pocketed at a junction.
     */
    RIDING_AGAIN,
}

/** Where the detector is in its own lifecycle, for the UI to render. */
enum class CrashState { IDLE, IMPACT_SUSPECTED, COUNTING_DOWN, CRASH_REPORTED }

/**
 * Thresholds. Every default here is a guess that wants real-world tuning — which is exactly why
 * the detector sits behind [CrashDetector] and takes its config as a parameter.
 */
data class CrashConfig(
    /**
     * Impact threshold, ~4 g. Deliberately below the ceiling of a phone accelerometer: many
     * ship a ±8 g part and clip well before a real motorcycle impact, so a threshold set at
     * "what a crash really measures" would never be reached on the hardware we actually have.
     * Hard braking peaks near 1 g and a pothole at 2–3 g, so 4 g still separates them.
     */
    val impactAccelerationMps2: Double = 40.0,

    /** How far the phone's orientation must swing from its riding baseline. A bike on its side. */
    val tiltChangeDegrees: Double = 45.0,

    /**
     * The detector only arms while the rider is actually riding: a phone dropped in a car park
     * produces a textbook impact spike and no crash.
     */
    val armingSpeedMps: Double = 5.0,

    /** How long after the last riding-speed fix an impact can still be attributed to a crash. */
    val armingWindow: Duration = 10.seconds,

    /** Time allowed after an impact for the tilt-and-stillness picture to complete. */
    val confirmWindow: Duration = 12.seconds,

    /** At or below this, the bike is not moving. */
    val stationarySpeedMps: Double = 1.0,

    /** How long the stillness must last. Long enough that a stall at a junction is not a crash. */
    val stillnessRequired: Duration = 5.seconds,

    /** The cancellable window before the group is told (spec 2.5). */
    val countdown: Duration = 30.seconds,
)

/**
 * Crash detection, behind an interface on purpose.
 *
 * Two reasons. First, tuning false positives will take many iterations on real rides, and
 * swapping the whole strategy must not touch anything else. Second, the app has to be able to
 * run with detection **off** — see [DisabledCrashDetector] — because a detector that fires
 * wrongly is worse than none at all, and that has to be a one-line substitution rather than a
 * flag threaded through the call sites.
 */
interface CrashDetector {
    val state: CrashState

    /** Feeds one sensor reading. Returns a signal only at the moments something changed. */
    fun onMotion(sample: MotionSample, location: LatLng?): CrashSignal?

    /** The rider tapped "I'm fine". */
    fun cancel(at: Instant): CrashSignal?

    /** Back to idle — call when a ride ends or after a confirmed crash has been dealt with. */
    fun reset()
}

/** Detection turned off. Exists so "off" is a real, testable state and not a special case. */
object DisabledCrashDetector : CrashDetector {
    override val state: CrashState get() = CrashState.IDLE
    override fun onMotion(sample: MotionSample, location: LatLng?): CrashSignal? = null
    override fun cancel(at: Instant): CrashSignal? = null
    override fun reset() = Unit
}
