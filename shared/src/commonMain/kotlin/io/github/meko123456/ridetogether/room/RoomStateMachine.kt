package io.github.meko123456.ridetogether.room

import io.github.meko123456.ridetogether.model.Role
import io.github.meko123456.ridetogether.model.RoomState
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Something a member asks the room to do. */
sealed interface RoomCommand {
    data object StartRide : RoomCommand
    data object PauseRide : RoomCommand
    data object ResumeRide : RoomCommand
    data object EndRide : RoomCommand
}

/** Why a command was refused — surfaced to the UI so it can say something useful. */
enum class RoomRejection {
    NOT_PERMITTED,
    ILLEGAL_TRANSITION,
    NOT_ENOUGH_RIDERS,
    ROOM_EXPIRED,
}

sealed interface RoomTransition {
    data class Accepted(val from: RoomState, val to: RoomState) : RoomTransition
    data class Rejected(val reason: RoomRejection) : RoomTransition
}

/**
 * The room lifecycle, as a pure function of (current state, who is asking, when).
 *
 * Keeping this out of the UI and out of the network layer means the rules are asserted once, in
 * tests, rather than re-implemented in a ViewModel and again in a Firebase security rule — and
 * the two drifting apart is how a rider ends up broadcasting their location from a room they
 * think has ended.
 */
object RoomStateMachine {

    /** A room stops accepting anything 24 h after creation (spec 2.1). */
    val LIFETIME: Duration = 24.hours

    /** An ended room lingers an hour so riders can read the summary, then expires. */
    val ENDED_GRACE: Duration = 1.hours

    /** Legal transitions, independent of who asks. */
    private val allowed: Map<RoomState, Set<RoomState>> = mapOf(
        RoomState.LOBBY to setOf(RoomState.RIDING, RoomState.ENDED),
        RoomState.RIDING to setOf(RoomState.PAUSED, RoomState.ENDED),
        RoomState.PAUSED to setOf(RoomState.RIDING, RoomState.ENDED),
        RoomState.ENDED to emptySet(),
    )

    fun target(command: RoomCommand): RoomState = when (command) {
        RoomCommand.StartRide -> RoomState.RIDING
        RoomCommand.PauseRide -> RoomState.PAUSED
        RoomCommand.ResumeRide -> RoomState.RIDING
        RoomCommand.EndRide -> RoomState.ENDED
    }

    fun canTransition(from: RoomState, to: RoomState): Boolean = to in (allowed[from] ?: emptySet())

    /**
     * Whether a room should be treated as gone: past its 24 h life, or an hour past ENDED.
     * Expiry is computed rather than stored so a client with a stale cache still refuses to
     * publish location into a dead room.
     */
    fun isExpired(state: RoomState, createdAt: Instant, endedAt: Instant?, now: Instant): Boolean {
        if (now - createdAt >= LIFETIME) return true
        if (state == RoomState.ENDED && endedAt != null && now - endedAt >= ENDED_GRACE) return true
        return false
    }

    /**
     * Decide a command.
     *
     * @param riderCount members currently in the room — a ride needs at least two, since the
     *   whole feature set is about the gap between riders.
     */
    fun decide(
        state: RoomState,
        role: Role?,
        command: RoomCommand,
        riderCount: Int,
        createdAt: Instant,
        endedAt: Instant?,
        now: Instant,
    ): RoomTransition {
        if (isExpired(state, createdAt, endedAt, now)) return RoomTransition.Rejected(RoomRejection.ROOM_EXPIRED)
        if (role == null || !role.canControlRoom) return RoomTransition.Rejected(RoomRejection.NOT_PERMITTED)
        val to = target(command)
        if (!canTransition(state, to)) return RoomTransition.Rejected(RoomRejection.ILLEGAL_TRANSITION)
        if (command == RoomCommand.StartRide && riderCount < MIN_RIDERS_TO_RIDE) {
            return RoomTransition.Rejected(RoomRejection.NOT_ENOUGH_RIDERS)
        }
        return RoomTransition.Accepted(state, to)
    }

    const val MIN_RIDERS_TO_RIDE = 2
}
