package io.github.meko123456.ridetogether.alerts

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tunables for [AlertEngine]. Every one of these is a guess until real rides produce numbers, so
 * they live in one place, are leader-configurable where the spec says so, and are injected into
 * tests rather than baked into the logic.
 */
data class AlertConfig(
    /** Gap at which a rider counts as separated (spec default 1.5 km, leader-configurable). */
    val gapThresholdMeters: Double = 1_500.0,

    /**
     * How long the gap must keep growing before we say anything. Handles a red light — but see
     * [AlertEngine] for why the grace period alone is not enough.
     */
    val gracePeriod: Duration = 60.seconds,

    /**
     * Clear the alert only once the gap falls to this fraction of the threshold. Without the
     * hysteresis band an alert flaps on and off around 1.5 km and the group stops believing it.
     */
    val clearFraction: Double = 0.75,

    /** How long the rider has to answer "all good?" before we escalate (spec 2.3.4). */
    val responseTimeout: Duration = 90.seconds,

    /** At or below this speed a rider counts as stopped (~5 km/h, so walking a bike is stopped). */
    val stationarySpeedMps: Double = 1.5,

    /**
     * A rider must look stationary for at least this long before it counts. One GPS sample
     * reading 0 m/s means nothing — parked, or a bad fix in a tunnel mouth, look identical for
     * a second.
     */
    val stationaryWindow: Duration = 30.seconds,

    /**
     * How long a rider must be *continuously* stationary before the loud group-wide alert can
     * fire. This is the real defence against a red light, and it is deliberately longer than the
     * display threshold above: a traffic light holds you for at most a couple of minutes, while
     * a breakdown or a crash holds you indefinitely. Nothing in a position trace can tell those
     * apart sooner — which is exactly why the cheap local prompt comes first and the loud alert
     * has to wait.
     */
    val minStationaryForIncident: Duration = 120.seconds,

    /**
     * Data older than this is stale. Deliberately expressed as a multiple of the rider's own
     * reporting interval as well as a floor, because the spec's flat 45 s collides with its own
     * adaptive intervals: a stopped rider reporting every 30 s (or every 15 s on battery saver)
     * is one dropped packet from greying out, and a group that sees grey constantly learns to
     * ignore it.
     */
    val staleAfter: Duration = 45.seconds,
    val staleIntervalMultiplier: Double = 2.5,

    /**
     * A rider who just joined needs this much history before the gap logic applies to them —
     * someone catching the group up starts kilometres behind by definition.
     *
     * This must be comfortably LONGER than [gracePeriod]: at 60 s each, a gap that has been
     * rising long enough to alert has also outlived the grace, so the knob would protect nobody.
     */
    val joinGrace: Duration = 150.seconds,

    /** Perpendicular distance beyond which a rider is treated as off-route. */
    val offRouteToleranceMeters: Double = RouteProjection.DEFAULT_OFF_ROUTE_TOLERANCE_M,
) {
    init {
        require(gapThresholdMeters > 0) { "gap threshold must be positive" }
        require(clearFraction in 0.0..1.0) { "clearFraction must be a fraction" }
    }

    /** The gap below which an existing alert is cleared. */
    val clearThresholdMeters: Double get() = gapThresholdMeters * clearFraction
}
