package io.github.meko123456.ridetogether.model

import kotlinx.datetime.Instant

/** One of the canned one-tap messages (spec 2.4). */
enum class QuickMessage(val text: String) {
    FUEL_STOP_NEEDED("Fuel stop needed"),
    SLOW_DOWN("Slow down"),
    PULL_OVER_NEXT_SAFE_SPOT("Pull over next safe spot"),
    ALL_GOOD("All good"),
}

/**
 * The append-only room event log. Every entry carries who and when so the feed reads as a
 * story of the ride, and so a rider who rejoins can catch up.
 */
sealed interface RideEvent {
    val at: Instant
    val riderId: String

    data class Joined(override val at: Instant, override val riderId: String) : RideEvent
    data class Left(override val at: Instant, override val riderId: String) : RideEvent

    data class StateChanged(
        override val at: Instant,
        override val riderId: String,
        val from: RoomState,
        val to: RoomState,
    ) : RideEvent

    data class FellBehind(
        override val at: Instant,
        override val riderId: String,
        val gapMeters: Double,
    ) : RideEvent

    data class Rejoined(override val at: Instant, override val riderId: String) : RideEvent

    data class Responded(
        override val at: Instant,
        override val riderId: String,
        val response: FallbackResponse,
    ) : RideEvent

    data class PossibleIncident(
        override val at: Instant,
        override val riderId: String,
        val location: LatLng?,
    ) : RideEvent

    data class SignalLost(override val at: Instant, override val riderId: String) : RideEvent

    data class SignalRestored(override val at: Instant, override val riderId: String) : RideEvent

    data class Message(
        override val at: Instant,
        override val riderId: String,
        val message: QuickMessage,
    ) : RideEvent

    data class BatterySaver(
        override val at: Instant,
        override val riderId: String,
        val batteryPercent: Int,
    ) : RideEvent
}
