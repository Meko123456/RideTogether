package io.github.meko123456.ridetogether.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.meko123456.ridetogether.model.JoinCode
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.Role
import io.github.meko123456.ridetogether.model.Room
import io.github.meko123456.ridetogether.model.RoomState
import io.github.meko123456.ridetogether.model.Visibility
import io.github.meko123456.ridetogether.room.JoinOutcome
import io.github.meko123456.ridetogether.room.JoinPolicy
import io.github.meko123456.ridetogether.room.JoinRefusal
import io.github.meko123456.ridetogether.room.RoomCommand
import io.github.meko123456.ridetogether.room.RoomRejection
import io.github.meko123456.ridetogether.room.RoomStateMachine
import io.github.meko123456.ridetogether.room.RoomTransition
import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * Drives the UI from the shared domain. Every decision here — who may start a ride, whether a
 * join is allowed, what a code resolves to — is delegated to `:shared`, which is the module the
 * tests cover. This class only holds the current room and turns rejections into sentences.
 *
 * Rooms live in memory for now. The realtime layer (issue #10) replaces [rooms] with Firebase
 * behind a `RealtimeClient` interface; until then a code resolves only against rides created on
 * this device, which is enough to exercise the real join path end to end.
 */
class RideViewModel : ViewModel() {

    /** Stands in for the signed-in rider until accounts land. */
    private val riderId = "me"

    private val rooms = mutableMapOf<String, Room>()

    var room by mutableStateOf<Room?>(null)
        private set

    var rideName by mutableStateOf("")
        private set

    var codeInput by mutableStateOf("")
        private set

    /** Last thing that happened that the rider should know about, shown once then cleared. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** What [codeInput] resolves to after Crockford normalisation, or null while it isn't a code. */
    val resolvedCode: JoinCode? get() = JoinCode.parseOrNull(codeInput)

    fun onRideNameChange(value: String) {
        rideName = value.take(40)
    }

    fun onCodeInputChange(value: String) {
        // Allow separators through so the rider can type what they see ("A2B-4C7"); the domain
        // strips them. Cap generously rather than at LENGTH so normalisation has room to work.
        codeInput = value.take(16)
        notice = null
    }

    fun consumeNotice() {
        notice = null
    }

    fun createRide() {
        val code = JoinCode.generate { bound -> Random.nextInt(bound) }
        val created = Room(
            id = "room-${code.value}",
            code = code,
            name = rideName.trim().ifBlank { "Ride" },
            visibility = Visibility.INVITE_ONLY,
            maxRiders = Room.MAX_RIDERS,
            state = RoomState.LOBBY,
            leaderId = riderId,
            members = listOf(Member(riderId = riderId, displayName = "You", role = Role.LEADER)),
            createdAt = Clock.System.now(),
        )
        rooms[code.value] = created
        room = created
        rideName = ""
    }

    fun joinByCode() {
        val code = resolvedCode ?: run {
            notice = "A ride code is ${JoinCode.LENGTH} characters — digits and letters, no I, L, O or U."
            return
        }
        val target = rooms[code.value] ?: run {
            notice = "No ride found for ${code.value}. Codes resolve over the network, which isn't " +
                "wired up yet — for now you can reopen a ride created on this phone."
            return
        }
        when (val outcome = JoinPolicy.evaluate(target, riderId, Clock.System.now())) {
            JoinOutcome.Admitted -> {
                val joined = target.copy(
                    members = target.members + Member(riderId = riderId, displayName = "You"),
                )
                save(joined)
                notice = "Joined ${joined.name}."
            }
            JoinOutcome.AwaitingApproval -> notice = "Asked the leader to let you in."
            JoinOutcome.AlreadyMember -> {
                room = target
                notice = "Reopened ${target.name} — ${code.value}."
            }
            is JoinOutcome.Refused -> notice = describe(outcome.reason)
        }
        codeInput = ""
    }

    fun leaveRoom() {
        room = null
    }

    /** Runs a lifecycle command through the state machine and reports whatever it decides. */
    fun send(command: RoomCommand) {
        val current = room ?: return
        val now = Clock.System.now()
        val transition = RoomStateMachine.decide(
            state = current.state,
            role = current.roleOf(riderId),
            command = command,
            riderCount = current.members.size,
            createdAt = current.createdAt,
            endedAt = current.endedAt,
            now = now,
        )
        when (transition) {
            is RoomTransition.Accepted -> save(
                current.copy(
                    state = transition.to,
                    endedAt = if (transition.to == RoomState.ENDED) now else current.endedAt,
                ),
            )
            is RoomTransition.Rejected -> notice = describe(transition.reason)
        }
    }

    /**
     * Names a rider as the sweep, or clears the flag by naming them again. Only one rider can
     * hold it, because the alert engine treats the sweep as legitimately last.
     */
    fun toggleSweep(targetRiderId: String) {
        val current = room ?: return
        val alreadySweep = current.member(targetRiderId)?.isSweep == true
        save(
            current.copy(
                members = current.members.map { it.copy(isSweep = !alreadySweep && it.riderId == targetRiderId) },
            ),
        )
    }

    /**
     * Adds a synthetic rider so the room lifecycle can be exercised on one phone — a ride needs
     * two riders before it can start. Goes away with the realtime layer.
     */
    fun addDemoRider() {
        val current = room ?: return
        val id = "demo-${current.members.size}"
        when (val outcome = JoinPolicy.evaluate(current, id, Clock.System.now())) {
            JoinOutcome.Admitted -> save(
                current.copy(
                    members = current.members + Member(
                        riderId = id,
                        displayName = DEMO_NAMES[(current.members.size - 1).coerceIn(DEMO_NAMES.indices)],
                    ),
                ),
            )
            JoinOutcome.AwaitingApproval -> notice = "They'd be waiting for the leader to approve them."
            JoinOutcome.AlreadyMember -> Unit
            is JoinOutcome.Refused -> notice = describe(outcome.reason)
        }
    }

    private fun save(updated: Room) {
        rooms[updated.code.value] = updated
        room = updated
    }

    private fun describe(reason: RoomRejection): String = when (reason) {
        RoomRejection.NOT_PERMITTED -> "Only the leader or a co-leader can do that."
        RoomRejection.ILLEGAL_TRANSITION -> "That isn't possible from here."
        RoomRejection.NOT_ENOUGH_RIDERS ->
            "A ride needs at least ${RoomStateMachine.MIN_RIDERS_TO_RIDE} riders before it can start."
        RoomRejection.ROOM_EXPIRED -> "This ride has expired — rides last ${RoomStateMachine.LIFETIME.inWholeHours} hours."
    }

    private fun describe(reason: JoinRefusal): String = when (reason) {
        JoinRefusal.ROOM_FULL -> "That ride is full (${Room.MAX_RIDERS} riders)."
        JoinRefusal.ROOM_ENDED -> "That ride has finished."
        JoinRefusal.ROOM_EXPIRED -> "That code has expired."
    }

    private companion object {
        val DEMO_NAMES = listOf("Giorgi", "Nika", "Ana", "Luka", "Saba", "Mari", "Dato", "Tazo", "Vato")
    }
}
