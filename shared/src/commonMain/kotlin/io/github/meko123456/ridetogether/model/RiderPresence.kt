package io.github.meko123456.ridetogether.model

import kotlinx.datetime.Instant

/**
 * How a rider currently stands relative to the group.
 *
 * [SIGNAL_LOST] is deliberately separate from [POSSIBLE_INCIDENT]: a phone going quiet is not
 * evidence of a crash, and conflating the two is how an app trains its users to ignore it
 * (spec 2.3.5).
 *
 * The spec's sketch also listed a PAUSED status; it is omitted here because it only duplicates
 * [io.github.meko123456.ridetogether.model.RoomState.PAUSED] and could therefore only ever
 * disagree with it. Whether the ride is paused is a property of the room, not of a rider.
 */
enum class RiderStatus {
    ACTIVE,
    FALLING_BEHIND,
    STOPPED,
    SIGNAL_LOST,
    POSSIBLE_INCIDENT,
}

/** One rider's last known position and derived state. */
data class RiderPresence(
    val riderId: String,
    val location: LatLng? = null,
    val headingDegrees: Float? = null,
    val speedMps: Float? = null,
    val batteryPercent: Int? = null,
    val updatedAt: Instant,
    val status: RiderStatus = RiderStatus.ACTIVE,
) {
    /** Metres per second → km/h, for display. */
    val speedKmh: Float? get() = speedMps?.let { it * 3.6f }
}

/** A rider's own answer to "you've dropped back — all good?" (spec 2.3.3). */
enum class FallbackResponse { FINE, MECHANICAL_ISSUE, NEED_HELP }
