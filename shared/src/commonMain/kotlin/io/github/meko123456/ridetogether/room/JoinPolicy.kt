package io.github.meko123456.ridetogether.room

import io.github.meko123456.ridetogether.model.Room
import io.github.meko123456.ridetogether.model.RoomState
import io.github.meko123456.ridetogether.model.Visibility
import kotlinx.datetime.Instant

/** The outcome of trying to join a room. */
sealed interface JoinOutcome {
    /** Straight in — invite-only with auto-accept, or a public room with auto-accept. */
    data object Admitted : JoinOutcome

    /** In the room's pending list until the leader decides. */
    data object AwaitingApproval : JoinOutcome

    /** Already a member: rejoining after a reinstall or a crash must not duplicate them. */
    data object AlreadyMember : JoinOutcome

    data class Refused(val reason: JoinRefusal) : JoinOutcome
}

enum class JoinRefusal {
    ROOM_FULL,
    ROOM_ENDED,
    ROOM_EXPIRED,
}

/**
 * Who may join a room, and whether the leader has to say yes.
 *
 * Joining a ride already in progress is allowed on purpose — someone catching the group up at
 * the second fuel stop is a normal thing, and refusing them would just mean they ride untracked.
 */
object JoinPolicy {

    fun evaluate(room: Room, riderId: String, now: Instant): JoinOutcome {
        if (room.member(riderId) != null) return JoinOutcome.AlreadyMember
        if (RoomStateMachine.isExpired(room.state, room.createdAt, room.endedAt, now)) {
            return JoinOutcome.Refused(JoinRefusal.ROOM_EXPIRED)
        }
        if (room.state == RoomState.ENDED) return JoinOutcome.Refused(JoinRefusal.ROOM_ENDED)
        if (room.isFull) return JoinOutcome.Refused(JoinRefusal.ROOM_FULL)
        // A public room is discoverable by strangers, so the leader's toggle is what decides;
        // an invite-only room already implies the joiner was given the code.
        return if (room.autoAcceptJoins) JoinOutcome.Admitted else JoinOutcome.AwaitingApproval
    }

    /** Deep link for a room code — `ridetogether://join/ABC234` (spec 2.1). */
    fun deepLink(room: Room): String = "$SCHEME://join/${room.code.value}"

    /** Pulls a code out of a deep link, or null if it isn't one of ours. */
    fun codeFromDeepLink(uri: String): String? {
        val prefix = "$SCHEME://join/"
        if (!uri.startsWith(prefix, ignoreCase = true)) return null
        return uri.removePrefix(prefix).trim('/').takeIf { it.isNotEmpty() }
    }

    const val SCHEME = "ridetogether"
}
