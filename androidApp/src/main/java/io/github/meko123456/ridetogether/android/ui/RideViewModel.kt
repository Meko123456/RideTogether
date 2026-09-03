package io.github.meko123456.ridetogether.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.viewModelScope
import io.github.meko123456.ridetogether.model.JoinCode
import io.github.meko123456.ridetogether.android.location.RideLocation
import io.github.meko123456.ridetogether.android.speech.RideSpeaker
import io.github.meko123456.ridetogether.model.QuickMessage
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.session.RideSession
import io.github.meko123456.ridetogether.session.SessionTick
import io.github.meko123456.ridetogether.android.history.RideHistory
import io.github.meko123456.ridetogether.android.history.StoredRide
import io.github.meko123456.ridetogether.summary.RideSummariser
import io.github.meko123456.ridetogether.summary.TracePoint
import io.github.meko123456.ridetogether.android.crash.CrashMonitor
import io.github.meko123456.ridetogether.crash.CrashSignal
import io.github.meko123456.ridetogether.alerts.RiderAssessment
import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.realtime.InMemoryRealtimeClient
import io.github.meko123456.ridetogether.realtime.RealtimeClient
import io.github.meko123456.ridetogether.realtime.RealtimeError
import io.github.meko123456.ridetogether.realtime.RealtimeResult
import kotlinx.coroutines.Job
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.Room
import io.github.meko123456.ridetogether.model.RoomState
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
 * Everything to do with rooms now goes through [RealtimeClient]. Today that is the in-memory
 * implementation, so a code still only resolves against rides created on this device — but the
 * *shape* is the real one: rooms arrive as a flow, positions arrive from the client rather than
 * straight off the phone's own sensor, and writes can fail with a reason. Swapping Firebase in
 * (#10) changes the construction of `client` and nothing in this class.
 */
class RideViewModel(application: Application) : AndroidViewModel(application) {

    /** Stands in for the signed-in rider until accounts land. */
    private val riderId = "me"

    /**
     * The backend. In-memory for now — see #10. Held as the interface type deliberately, so
     * nothing here can reach for a capability the real implementation will not have.
     */
    private val client: RealtimeClient = InMemoryRealtimeClient(selfId = riderId)

    /** Only the demo affordances need the concrete type, and only to fake other riders. */
    private val fakeOthers: InMemoryRealtimeClient? get() = client as? InMemoryRealtimeClient

    private var roomWatch: Job? = null
    private var positionWatch: Job? = null

    private val session = RideSession(selfId = riderId)

    /** Guards against stacking follow-up ticks when several events arrive together. */
    private var followUpScheduled = false

    private val history = RideHistory(application)
    private val summariser = RideSummariser()

    /**
     * This ride's positions, kept only until the ride ends and a summary is made from them.
     * Deliberately never persisted: the trace is the sensitive part, and the summary is the only
     * thing worth keeping (see docs/PRIVACY.md).
     */
    private val trace = mutableListOf<TracePoint>()

    /** Everyone's latest position, straight from the client — the alert engine's input. */
    var positions by mutableStateOf<Map<String, RiderSample>>(emptyMap())
        private set

    /** What the engine believes about each rider, for the map's colours and the rider list. */
    var assessments by mutableStateOf<List<RiderAssessment>>(emptyList())
        private set

    /** Finished rides, newest first. */
    var rides by mutableStateOf<List<StoredRide>>(emptyList())
        private set

    /** The summary of the ride that just ended, shown once. */
    var lastSummary by mutableStateOf<StoredRide?>(null)
        private set

    /** A crash countdown in progress, or a confirmed crash waiting to be acknowledged. */
    var crashSignal by mutableStateOf<CrashSignal?>(null)
        private set

    init {
        rides = history.load()
        viewModelScope.launch {
            CrashMonitor.signal.collect { signal ->
                crashSignal = signal
                when (signal) {
                    is CrashSignal.CrashConfirmed -> {
                        // Into the feed and, because it is CRITICAL, straight out of the speaker.
                        record(RideEvent.PossibleIncident(signal.at, riderId, signal.location))
                    }
                    else -> Unit
                }
            }
        }
    }
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
        val name = rideName.trim().ifBlank { "Ride" }
        viewModelScope.launch {
            when (val result = client.createRoom(name, code, Clock.System.now())) {
                is RealtimeResult.Success -> {
                    rideName = ""
                    watch(result.value.id)
                }
                is RealtimeResult.Failure -> notice = describe(result.error)
            }
        }
    }

    fun joinByCode() {
        val code = resolvedCode ?: run {
            notice = "A ride code is ${JoinCode.LENGTH} characters — digits and letters, no I, L, O or U."
            return
        }
        viewModelScope.launch {
            when (val found = client.findRoom(code)) {
                is RealtimeResult.Failure -> notice = describe(found.error)
                is RealtimeResult.Success -> {
                    val target = found.value
                    if (target == null) {
                        notice = "No ride found for ${code.value}. Codes resolve over the network, " +
                            "which isn't wired up yet — for now you can reopen a ride created on this phone."
                        return@launch
                    }
                    val joined = client.join(
                        roomId = target.id,
                        member = Member(riderId = riderId, displayName = "You"),
                        now = Clock.System.now(),
                    )
                    when (joined) {
                        is RealtimeResult.Success -> {
                            watch(joined.value.id)
                            codeInput = ""
                            notice = "Joined ${joined.value.name}."
                        }
                        is RealtimeResult.Failure -> notice = describe(joined.error)
                    }
                }
            }
        }
    }

    fun leaveRoom() {
        val current = room
        roomWatch?.cancel()
        positionWatch?.cancel()
        room = null
        feed = emptyList()
        trace.clear()
        if (current != null) {
            viewModelScope.launch { client.leave(current.id, Clock.System.now()) }
        }
    }

    /**
     * Follows one room: its state, and everyone's positions.
     *
     * Both arrive as flows from the client rather than being held locally, so the app reacts to a
     * room changing under it — someone else ending the ride, the room expiring — the same way it
     * will once those changes come from the network rather than from this phone.
     */
    private fun watch(roomId: String) {
        roomWatch?.cancel()
        positionWatch?.cancel()
        roomWatch = viewModelScope.launch {
            client.observeRoom(roomId).collect { updated ->
                if (updated == null && room != null) {
                    notice = "That ride is no longer there."
                    room = null
                    return@collect
                }
                room = updated
            }
        }
        positionWatch = viewModelScope.launch {
            client.observePositions(roomId).collect { positions -> onPositions(positions) }
        }
        // This phone's own fixes go *to* the client and come back through the flow above, which is
        // exactly the path they will take once there is a network in between.
        viewModelScope.launch {
            RideLocation.own.collect { sample ->
                val here = room ?: return@collect
                if (sample != null && here.state.sharesLocation) {
                    client.publishPosition(here.id, sample)
                }
            }
        }
    }

    /** Everyone's latest positions: remembered for the summary, and fed to the engine. */
    private fun onPositions(positions: Map<String, RiderSample>) {
        val current = room ?: return
        positions[riderId]?.let { own ->
            if (current.state.sharesLocation) {
                trace += TracePoint(own.at, own.location, own.speedMps?.toDouble())
            }
        }
        this.positions = positions
        tickSession(emptyList())
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
                viewModelScope.launch {
                    val result = client.setState(current.id, transition.to, now)
                    if (result is RealtimeResult.Failure) notice = describe(result.error)
                }
                record(RideEvent.StateChanged(now, riderId, transition.from, transition.to))
                if (transition.to == RoomState.ENDED) {
                    // Nothing should still be talking about a ride that is over.
                    speaker.stop()
                    session.reset()
                    finishRide(current)
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
        // Local until the client grows a sweep call: it changes what the alert engine believes
        // rather than what the backend enforces, so it is not urgent, but it does mean the flag
        // will not survive a room arriving fresh from the network. Noted rather than hidden.
        room = current.copy(
            members = current.members.map { it.copy(isSweep = !alreadySweep && it.riderId == targetRiderId) },
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
            JoinOutcome.Admitted, JoinOutcome.AwaitingApproval -> fakeOthers?.receiveMember(
                current.id,
                Member(
                    riderId = id,
                    displayName = DEMO_NAMES[(current.members.size - 1).coerceIn(DEMO_NAMES.indices)],
                ),
            )
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
        feed = (listOf(event) + feed).take(MAX_FEED)
        tickSession(listOf(event))
    }

    /**
     * Runs one session tick and speaks whatever comes out.
     *
     * Called after anything that could produce an announcement rather than on a timer: without the
     * realtime layer there are no incoming positions, so nothing changes unless this app changed
     * it. The exception is a held line — the announcer keeps one back while the channel is busy and
     * has no clock of its own, so when it says something is pending we come back once the quiet
     * period has passed. Without that the deferral would wait for whatever event happened to
     * arrive next, which on a quiet ride could be minutes.
     */
    private fun tickSession(events: List<RideEvent>) {
        val current = room ?: return
        val result = session.tick(
            SessionTick(
                now = Clock.System.now(),
                roomState = current.state,
                members = current.members,
                samples = positions,
                batteryPercent = null,
                events = events,
            ),
            nameOf = { id -> current.member(id)?.displayName ?: "A rider" },
        )
        assessments = result.assessments
        speaker.speak(result.announcements)
        if (result.pendingAnnouncement && !followUpScheduled) {
            followUpScheduled = true
            viewModelScope.launch {
                delay(FOLLOW_UP_DELAY_MS)
                followUpScheduled = false
                tickSession(emptyList())
            }
        }
    }

    /**
     * Turns the ride's trace into a summary, stores it, and drops the trace.
     *
     * Called on ENDED rather than on leaving the room: leaving is not finishing, and a summary of
     * a ride you walked away from halfway through would be wrong about the ride.
     */
    private fun finishRide(room: Room) {
        if (trace.size < 2) {
            trace.clear()
            return
        }
        val summary = summariser.summarise(room.id, mapOf(riderId to trace.toList()))
        lastSummary = history.save(room.id, room.name, summary)
        rides = history.load()
        trace.clear()
    }

    fun dismissSummary() {
        lastSummary = null
    }

    /** "I'm fine" — the whole reason a detector is allowed to be wrong. */
    fun cancelCrashCountdown() {
        CrashMonitor.cancel(Clock.System.now())
        crashSignal = null
        CrashMonitor.consumeSignal()
    }

    fun acknowledgeCrash() {
        crashSignal = null
        CrashMonitor.consumeSignal()
    }

    /**
     * Drives the real detector with a synthetic impact so the countdown can be tested without
     * crashing a motorcycle. It goes through the genuine arming conditions, so if those are not
     * met nothing happens — which is itself the useful thing to see.
     */
    fun simulateImpact() {
        if (room?.state?.sharesLocation != true) {
            notice = "Start the ride first — crash detection is only armed during one."
            return
        }
        CrashMonitor.simulateImpact(Clock.System.now())
    }

    fun voiceStatus(): String = speaker.status()

    override fun onCleared() {
        speaker.release()
        super.onCleared()
    }

    private fun describe(error: RealtimeError): String = when (error) {
        RealtimeError.OFFLINE -> "No connection. It will retry."
        RealtimeError.ROOM_GONE -> "That ride is no longer there."
        RealtimeError.NOT_PERMITTED -> "That ride would not accept the change."
        RealtimeError.CODE_TAKEN -> "That code is already in use — try again."
        RealtimeError.UNKNOWN -> "That did not work."
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
        /** A shade past the announcer's quiet period, so the channel is genuinely free. */
        const val FOLLOW_UP_DELAY_MS = 21_000L
        const val MAX_FEED = 50
        val DEMO_NAMES = listOf("Giorgi", "Nika", "Ana", "Luka", "Saba", "Mari", "Dato", "Tazo", "Vato")
    }
}
