package io.github.meko123456.ridetogether.location

import io.github.meko123456.ridetogether.model.Geo
import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.model.RoomState
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the phone currently knows about itself. Nulls mean "not known yet", not "zero". */
data class LocationConditions(
    val roomState: RoomState,
    /** Ground speed from the last fix, or null before there is one. */
    val speedMps: Double?,
    /** Battery level 0..100, or null when it could not be read. */
    val batteryPercent: Int?,
)

/**
 * How often to ask for a position, and whether a fix is worth publishing.
 *
 * Pure, because this is the logic the spec calls out as highest-value to unit test (§6) and
 * because it decides the app's battery cost — a four-hour ride on under 40 % (§5) is won or lost
 * here, and it should not take a four-hour ride to find out it was got wrong.
 *
 * ## Two places the spec does not survive being implemented literally
 *
 * **1. "battery < 20 % → every 15 s" reads as a floor, and must be a cap.** Taken at face value it
 * makes a *stopped* rider on a dying battery report every 15 s where a healthy stopped rider
 * reports every 30 s — the low-battery rule would double the drain it exists to prevent. So a low
 * battery means "never faster than 15 s", applied as a maximum against whatever the movement
 * state asked for.
 *
 * **2. The spec covers "> 20 km/h" and "stopped", and nothing between.** City riding lives in that
 * gap: at 15 km/h a 30-second interval puts 125 m between fixes, which is a map that lies and a
 * gap calculation built on guesses. There is a middle band.
 */
object LocationPolicy {

    /** Spec §3.4 says 3–5 s while moving; the middle of that is the default. */
    val MOVING_INTERVAL: Duration = 4.seconds

    /** Traffic and car parks: often enough to be honest, rarely enough to be cheap. */
    val SLOW_INTERVAL: Duration = 10.seconds

    /** Spec §3.4. A parked bike does not need attention. */
    val STOPPED_INTERVAL: Duration = 30.seconds

    /** Spec §3.4's low-battery rule, as a cap on frequency rather than a floor. */
    val LOW_BATTERY_MINIMUM_INTERVAL: Duration = 15.seconds

    /** 20 km/h, spec §3.4. */
    const val MOVING_SPEED_MPS = 5.56

    /** Matches the alert engine's idea of stationary, so the two never disagree about a stop. */
    const val STATIONARY_SPEED_MPS = 1.5

    const val LOW_BATTERY_PERCENT = 20

    /** A jump this large is published immediately rather than waiting for the next tick. */
    const val SIGNIFICANT_MOVE_METERS = 75.0

    /**
     * How often to report, or **null when the app must not be collecting location at all** —
     * which is the kill switch, and the reason it lives here rather than in the service: it is a
     * property of the room state, testable without a device.
     */
    fun intervalFor(conditions: LocationConditions): Duration? {
        if (!conditions.roomState.sharesLocation) return null
        val byMovement = when {
            // No fix yet, so no speed. Report quickly: the alert engine needs a few positions
            // before it can judge anything, and the first seconds of a ride are the cheapest
            // time to be generous.
            conditions.speedMps == null -> MOVING_INTERVAL
            conditions.speedMps >= MOVING_SPEED_MPS -> MOVING_INTERVAL
            conditions.speedMps > STATIONARY_SPEED_MPS -> SLOW_INTERVAL
            else -> STOPPED_INTERVAL
        }
        return if (isBatterySaver(conditions.batteryPercent)) {
            maxOf(byMovement, LOW_BATTERY_MINIMUM_INTERVAL)
        } else {
            byMovement
        }
    }

    /** True when the room should be told this rider has gone into battery saving (spec §3.4). */
    fun isBatterySaver(batteryPercent: Int?): Boolean =
        batteryPercent != null && batteryPercent < LOW_BATTERY_PERCENT

    /**
     * Whether this fix is worth sending.
     *
     * The provider delivers more often than the interval asks — it has no reason not to — so the
     * decision of what reaches the network is made here. A fix goes out when the interval has
     * elapsed, when the rider has moved a long way since the last one (so a sudden burst of speed
     * is not hidden behind a timer), or when it is the first fix of the ride.
     */
    fun shouldPublish(
        previous: LatLng?,
        previousAt: Instant?,
        candidate: LatLng,
        at: Instant,
        interval: Duration,
    ): Boolean {
        if (previous == null || previousAt == null) return true
        if (at - previousAt >= interval) return true
        return Geo.distanceMeters(previous, candidate) >= SIGNIFICANT_MOVE_METERS
    }
}
