package io.github.meko123456.ridetogether.summary

import io.github.meko123456.ridetogether.model.Geo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Turns recorded traces into a ride summary (spec 2.6).
 *
 * Three things here are opinions rather than arithmetic, and all three exist because the obvious
 * implementation produces numbers a rider will not believe:
 *
 * **1. Average speed excludes stops.** A spirited two-hour ride with a lunch stop has a
 * wall-clock average around 40 km/h, which reads as though the app mis-measured everything. The
 * moving average is the number that matches the ride the rider remembers — and the stopped time
 * is reported separately, so nothing is hidden to make it look better.
 *
 * **2. Implausible fixes are discarded, and counted.** A single bad GPS fix teleports a few
 * hundred metres and back; taken literally it adds distance and reports a max speed of 300 km/h.
 * Segments implying more than [SummaryConfig.maxPlausibleSpeedMps] are dropped, as are segments
 * across a coverage gap longer than [SummaryConfig.maxSegmentGap] — we genuinely do not know what
 * happened in a two-minute hole, and guessing a straight line through it is how a ride "gains"
 * a kilometre through a tunnel. The count of what was thrown away is part of the output, because
 * a trace with dozens of discards should not present itself as precise.
 *
 * **3. Max speed is median-filtered.** The single fastest fix in a trace is almost always noise,
 * and it is also the number a rider is most likely to screenshot. Taking the maximum of a
 * three-point median means a genuine fast section (several consecutive fixes) survives while a
 * lone spike cannot.
 */
class RideSummariser(private val config: SummaryConfig = SummaryConfig()) {

    fun summarise(roomId: String, traces: Map<String, List<TracePoint>>): RideSummary {
        val riders = traces
            .filterValues { it.isNotEmpty() }
            .map { (riderId, points) -> summariseRider(riderId, points) }
            .sortedByDescending { it.distanceMeters }

        val allPoints = traces.values.flatten()
        val startedAt = allPoints.minOfOrNull { it.at }
        val endedAt = allPoints.maxOfOrNull { it.at }

        return RideSummary(
            roomId = roomId,
            startedAt = startedAt,
            endedAt = endedAt,
            elapsed = if (startedAt != null && endedAt != null) endedAt - startedAt else ZERO,
            distanceMeters = riders.maxOfOrNull { it.distanceMeters } ?: 0.0,
            riders = riders,
        )
    }

    private fun summariseRider(riderId: String, unsorted: List<TracePoint>): RiderSummary {
        val points = unsorted.sortedBy { it.at }
        var distance = 0.0
        var discarded = 0
        var movingDuration = ZERO
        var stoppedDuration = ZERO
        var stopCount = 0
        var currentStop = ZERO
        val speeds = mutableListOf<Double>()

        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            val dt = current.at - previous.at
            if (dt <= ZERO) {
                // Duplicate or out-of-order timestamps carry no information about movement.
                discarded++
                continue
            }
            val step = Geo.distanceMeters(previous.location, current.location)
            val implied = step / dt.inWholeMilliseconds.toDouble() * 1_000.0

            if (dt > config.maxSegmentGap || implied > config.maxPlausibleSpeedMps) {
                discarded++
                // A stop cannot be measured across a hole either: close it without counting.
                if (currentStop >= config.minStopDuration) stopCount++
                currentStop = ZERO
                continue
            }

            distance += step
            // Moving or stopped is decided by *displacement over time*, not by the provider's
            // reported speed. The two disagree in a case that matters: a rider parked for a
            // minute whose next fix reports 20 m/s because they have just pulled away. Trusting
            // that reading books the whole stationary minute as riding, and the moving average
            // sags for a stop it was supposed to exclude. Displacement cannot lie about having
            // stayed put. The reported speed is still preferred for the max-speed series, where
            // an instantaneous figure is exactly what is wanted.
            speeds += current.speedMps ?: implied

            if (implied <= config.stopSpeedMps) {
                stoppedDuration += dt
                currentStop += dt
            } else {
                movingDuration += dt
                if (currentStop >= config.minStopDuration) stopCount++
                currentStop = ZERO
            }
        }
        // A ride that ends while stationary still ends with a stop.
        if (currentStop >= config.minStopDuration) stopCount++

        return RiderSummary(
            riderId = riderId,
            distanceMeters = distance,
            movingDuration = movingDuration,
            stoppedDuration = stoppedDuration,
            averageMovingSpeedMps = averageSpeed(distance, movingDuration),
            maxSpeedMps = smoothedMax(speeds),
            stopCount = stopCount,
            discardedPoints = discarded,
        )
    }

    private fun averageSpeed(distance: Double, moving: Duration): Double? {
        if (moving <= ZERO) return null
        return distance / (moving.inWholeMilliseconds.toDouble() / 1_000.0)
    }

    /**
     * Maximum of a three-point median. A lone spike is outvoted by its neighbours; a genuinely
     * fast stretch spans several fixes and comes through untouched. With fewer than three points
     * there is nothing to cross-check against, so the plain maximum is all that can be said.
     */
    private fun smoothedMax(speeds: List<Double>): Double? {
        if (speeds.isEmpty()) return null
        if (speeds.size < 3) return speeds.max()
        var best = 0.0
        for (index in 1 until speeds.size - 1) {
            val window = listOf(speeds[index - 1], speeds[index], speeds[index + 1]).sorted()
            if (window[1] > best) best = window[1]
        }
        return best
    }
}
