package io.github.meko123456.ridetogether.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.meko123456.ridetogether.model.JoinCode
import io.github.meko123456.ridetogether.android.location.RideLocation
import io.github.meko123456.ridetogether.android.speech.RideSpeaker
import io.github.meko123456.ridetogether.model.QuickMessage
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.session.RideSession
import io.github.meko123456.ridetogether.session.SessionTick
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
class RideViewModel(application: Application) : AndroidViewModel(application) {

    /** Stands in for the signed-in rider until accounts land. */
    private val riderId = "me"

    private val rooms = mutableMapOf<String, Room>()

    private val session = RideSession(selfId = riderId)
    private val speaker = RideSpeaker(application).also { it.configure() }

    /** The append-only ride log (spec 2.4). Newest first, because that is how it is read. */
    var feed by mutableStateOf<List<RideEvent>>(emptyList())
        private set

    var room by mutableStateOf<Room?>(null)
        private set

    var rideName by mutableStateOf("")
        private set

    var codeInput by mutableStateOf("")
        private set

    /** Last thing that happened that the rider should know about, shown once then cleared. */
    var notice by mutableStateOf<String?>(null)
        private set

    /**
     * True while the disclosure dialog should be on screen. Play requires it *before* the runtime
     * request, so the permission is never asked for until this has been shown and accepted.
     */
    var showLocationDisclosure by mutableStateOf(false)
        private set

    /** Set when the rider has seen the disclosure and tapped Continue this session. */
    private var disclosureAccepted = false

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

    /**
     * Called when a ride is about to start and location is not yet granted. Returns true when the
     * caller should show the disclosure first rather than requesting the permission.
     */
    fun needsDisclosure(permissionGranted: Boolean): Boolean =
        !permissionGranted && !disclosureAccepted

    fun requestDisclosure() {
        showLocationDisclosure = true
    }

    fun onDisclosureAccepted() {
        disclosureAccepted = true
        showLocationDisclosure = false
    }

    fun onDisclosureDeclined() {
        showLocationDisclosure = false
        // Not a dead end: the ride still works, the rider simply is not on the map.
        notice = "The ride will run without your position on the map. You can allow location later."
    }

    /**
     * Approximate location is not a lesser version of what this app needs — it is unusable. An
     * error of a kilometre or more says nothing about a 1.5 km gap, so the honest response is to
     * say so rather than draw a marker that is wrong by more than the thing being measured.
     */
    fun onApproximateLocationOnly() {
        notice = "Approximate location is not accurate enough to see the group. " +
            "Choose Precise in Android's location settings for RideTogether."
    }

    /**
     * An invite arrived from a `ridetogether://join/<CODE>` link. The code is filled in rather
     * than acted on, so the rider sees which ride they are about to enter.
     *
     * If they are already in a ride, that field is not on screen — so say something. Yanking
     * someone out of a ride in progress because they tapped a link would be worse, but a tap
     * that appears to do nothing is its own kind of broken.
     */
    fun onInviteReceived(rawCode: String) {
        onCodeInputChange(rawCode)
        if (room == null) return
        val resolved = JoinCode.parseOrNull(rawCode)
        notice = if (resolved != null) {
            "Invite for ${resolved.value} — leave this ride first to join it."
        } else {
            "That invite link isn't a valid ride code."
        }
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
            is RoomTransition.Accepted -> {
                save(
                    current.copy(
                        state = transition.to,
                        endedAt = if (transition.to == RoomState.ENDED) now else current.endedAt,
                    ),
                )
                record(RideEvent.StateChanged(now, riderId, transition.from, transition.to))
                if (transition.to == RoomState.ENDED) {
                    // Nothing should still be talking about a ride that is over.
                    speaker.stop()
                    session.reset()
                }
            }
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

    /** Sends a one-tap message to the room (spec 2.4). */
    fun send(message: QuickMessage) {
        val current = room ?: return
        if (!current.state.sharesLocation) {
            notice = "Messages are for a ride in progress."
            return
        }
        record(RideEvent.Message(Clock.System.now(), riderId, message))
        notice = "Sent: ${message.text}"
    }

    /**
     * Stands in for another rider messaging the room until the realtime layer lands. Exists
     * because the announcer deliberately never reads your own message back to you, so without
     * a second rider there is no way to hear the audio path work at all.
     */
    fun simulateMessageFromAnother(message: QuickMessage) {
        val current = room ?: return
        val other = current.members.firstOrNull { it.riderId != riderId } ?: run {
            notice = "Add a rider first — a message from yourself is not read back to you."
            return
        }
        record(RideEvent.Message(Clock.System.now(), other.riderId, message))
    }

    /**
     * Runs one session tick and speaks whatever comes out. Called after anything that could
     * produce an announcement, rather than on a timer: without the realtime layer there are no
     * incoming positions, so nothing changes unless this app changed it.
     */
    private fun record(event: RideEvent) {
        val current = room ?: return
        feed = (listOf(event) + feed).take(MAX_FEED)
        val result = session.tick(
            SessionTick(
                now = event.at,
                roomState = current.state,
                members = current.members,
                samples = RideLocation.own.value?.let { mapOf(riderId to it) } ?: emptyMap(),
                batteryPercent = null,
                events = listOf(event),
            ),
            nameOf = { id -> current.member(id)?.displayName ?: "A rider" },
        )
        speaker.speak(result.announcements)
    }

    fun voiceStatus(): String = speaker.status()

    override fun onCleared() {
        speaker.release()
        super.onCleared()
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
        const val MAX_FEED = 50
        val DEMO_NAMES = listOf("Giorgi", "Nika", "Ana", "Luka", "Saba", "Mari", "Dato", "Tazo", "Vato")
    }
}
