package io.github.meko123456.ridetogether.model

import kotlinx.datetime.Instant

/** Who can find and join a room. */
enum class Visibility { INVITE_ONLY, PUBLIC }

/**
 * Room lifecycle. Location sharing is active only in [RIDING] and [PAUSED] — that rule is the
 * app's privacy promise, so it lives in the domain rather than in the UI.
 */
enum class RoomState {
    LOBBY,
    RIDING,
    PAUSED,
    ENDED,
    ;

    /** True when members should be publishing their location. */
    val sharesLocation: Boolean get() = this == RIDING || this == PAUSED

    /** Fallback alerts are suppressed while paused (spec 2.3.6) and outside a ride. */
    val alertsActive: Boolean get() = this == RIDING
}

/**
 * What a member may do.
 *
 * Note that "sweep" is deliberately NOT a role: being the designated last rider changes how the
 * alert engine treats you, not what you are allowed to do, and a co-leader can also be the
 * sweep. Folding it in here would make that legitimate combination unrepresentable — so it is a
 * separate flag on [Member].
 */
enum class Role {
    LEADER,
    CO_LEADER,
    RIDER,
    ;

    /** Leader and co-leader may change room state and settings. */
    val canControlRoom: Boolean get() = this == LEADER || this == CO_LEADER
}

/**
 * A member of a room.
 *
 * @property isSweep the designated last rider. Orthogonal to [role] on purpose: it changes alert
 *   semantics (being at the back is their job) rather than permissions, and the sweep may also
 *   be a co-leader.
 */
data class Member(
    val riderId: String,
    val displayName: String,
    val role: Role = Role.RIDER,
    val isSweep: Boolean = false,
    val colorArgb: Int? = null,
    val motorcycle: String? = null,
)

/**
 * A ride room. [route] is the leader's planned polyline when one was set — the alert engine
 * prefers along-route gaps and falls back to straight-line distance when it is null.
 */
data class Room(
    val id: String,
    val code: JoinCode,
    val name: String,
    val visibility: Visibility,
    val maxRiders: Int,
    val state: RoomState,
    val leaderId: String,
    val members: List<Member> = emptyList(),
    val route: List<LatLng>? = null,
    val meetingPoint: LatLng? = null,
    val autoAcceptJoins: Boolean = true,
    val createdAt: Instant,
    val endedAt: Instant? = null,
) {
    init {
        require(maxRiders in MIN_RIDERS..MAX_RIDERS) { "maxRiders must be $MIN_RIDERS..$MAX_RIDERS, was $maxRiders" }
    }

    val isFull: Boolean get() = members.size >= maxRiders

    fun member(riderId: String): Member? = members.firstOrNull { it.riderId == riderId }

    fun roleOf(riderId: String): Role? = member(riderId)?.role

    /** The designated last rider, if the group named one. */
    val sweep: Member? get() = members.firstOrNull { it.isSweep }

    companion object {
        const val MIN_RIDERS = 2
        const val MAX_RIDERS = 10
    }
}
