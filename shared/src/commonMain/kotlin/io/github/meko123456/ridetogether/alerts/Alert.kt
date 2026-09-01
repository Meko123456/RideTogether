package io.github.meko123456.ridetogether.alerts

import io.github.meko123456.ridetogether.model.LatLng
import kotlinx.datetime.Instant

/**
 * What the engine decided. These are *transitions*, emitted once when something changes, so the
 * caller can announce them (TTS, notification, event feed) without having to diff state itself.
 */
sealed interface Alert {
    val riderId: String
    val at: Instant

    /** The rider has genuinely dropped back. Prompt them: "you've dropped back — all good?" */
    data class FallingBehind(
        override val riderId: String,
        override val at: Instant,
        val gapMeters: Double,
    ) : Alert

    /** They closed the gap again. Worth saying so the group can stop worrying. */
    data class Rejoined(
        override val riderId: String,
        override val at: Instant,
    ) : Alert

    /**
     * Dropped back, did not answer, and is not moving. This is the loud one, so everything above
     * exists to make sure it is rare and believable.
     */
    data class PossibleIncident(
        override val riderId: String,
        override val at: Instant,
        val location: LatLng?,
        val lastSeenAt: Instant,
    ) : Alert

    /**
     * Their data went quiet. Explicitly NOT an incident: a phone losing signal in a valley and a
     * rider in a ditch look the same from here, and pretending otherwise trains the group to
     * ignore the alert that matters (spec 2.3.5).
     */
    data class SignalLost(
        override val riderId: String,
        override val at: Instant,
        val lastSeenAt: Instant,
    ) : Alert

    data class SignalRestored(
        override val riderId: String,
        override val at: Instant,
    ) : Alert
}
