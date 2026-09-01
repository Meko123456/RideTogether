package io.github.meko123456.ridetogether.summary

import io.github.meko123456.ridetogether.model.LatLng
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One recorded point on a rider's trace. [speedMps] is the provider's own figure when it had one. */
data class TracePoint(
    val at: Instant,
    val location: LatLng,
    val speedMps: Double? = null,
)

/**
 * What one rider did.
 *
 * @property movingDuration time spent above the stopped threshold. [averageMovingSpeedMps] is
 *   derived from this rather than from wall-clock time, deliberately — see [RideSummariser].
 * @property stoppedDuration total time stationary, including the short waits that are not
 *   counted as [stopCount].
 * @property stopCount stops long enough to be worth mentioning, not every traffic light.
 */
data class RiderSummary(
    val riderId: String,
    val distanceMeters: Double,
    val movingDuration: Duration,
    val stoppedDuration: Duration,
    val averageMovingSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val stopCount: Int,
    /** Fixes discarded as implausible. Surfaced rather than hidden: a trace with many is suspect. */
    val discardedPoints: Int,
)

/**
 * The ride as a whole.
 *
 * @property distanceMeters how far the ride went, taken as the furthest any single rider actually
 *   rode. A rider who joined at the halfway point has a smaller number of their own, and both are
 *   true — averaging them would describe a ride nobody took.
 */
data class RideSummary(
    val roomId: String,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val elapsed: Duration,
    val distanceMeters: Double,
    val riders: List<RiderSummary>,
) {
    val isEmpty: Boolean get() = riders.isEmpty() || startedAt == null
}

/** Tunables for turning a trace into a summary. */
data class SummaryConfig(
    /** At or below this the bike is not moving. */
    val stopSpeedMps: Double = 1.0,

    /**
     * How long a halt must last to count as a *stop*. A minute keeps traffic lights out of the
     * total — "you stopped 34 times" is technically true and completely useless.
     */
    val minStopDuration: Duration = 60.seconds,

    /**
     * Anything implying more than this is a bad fix, not a fast rider: ~270 km/h. GPS glitches
     * routinely teleport a few hundred metres between fixes, which would otherwise add kilometres
     * to the distance and put an absurd number on the max speed.
     */
    val maxPlausibleSpeedMps: Double = 75.0,

    /**
     * Largest gap between fixes still treated as continuous riding. Beyond it the rider was out
     * of coverage and we cannot claim to know what they did, so the segment is not counted.
     */
    val maxSegmentGap: Duration = 120.seconds,
)
