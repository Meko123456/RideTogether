package io.github.meko123456.ridetogether.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.meko123456.ridetogether.android.crash.CrashMonitor
import io.github.meko123456.ridetogether.android.location.RideLocation
import io.github.meko123456.ridetogether.android.speech.RideSpeaker
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.meko123456.ridetogether.model.JoinCode
import io.github.meko123456.ridetogether.model.QuickMessage
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.session.RideSession
import io.github.meko123456.ridetogether.session.SessionTick
import io.github.meko123456.ridetogether.android.history.RideHistory
import io.github.meko123456.ridetogether.android.history.StoredRide
import io.github.meko123456.ridetogether.summary.RideSummariser
import io.github.meko123456.ridetogether.summary.TracePoint
import io.github.meko123456.ridetogether.crash.CrashSignal
import io.github.meko123456.ridetogether.alerts.RiderAssessment
import io.github.meko123456.ridetogether.alerts.RiderSample
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
 * Everything to do with rooms goes through [RealtimeClient]: rooms arrive as a flow, positions
 * arrive from the client rather than straight off the phone's own sensor, and writes can fail with
 * a reason. Which client that is, Firebase or this phone's memory, the build decides (#10), and
 * nothing in this class knows or cares.
 */
class RideViewModel(
    /**
     * The backend. Held as the interface type deliberately, so nothing here can reach for a
     * capability one implementation has and the other does not.
     */
    private val client: RealtimeClient,
    private val speaker: Voice,
    private val history: RideHistory,
    private val ownLocation: OwnLocation,
    private val crash: CrashDetection,
) : ViewModel() {

    /** Who this phone is: whoever the client writes as, so the two can never disagree. */
    private val riderId: String = client.selfId

    /** The same, for the map, which marks this phone's own position differently. */
    val selfId: String get() = riderId

    /** Only the demo affordances need the concrete type, and only to fake other riders. */
    private val fakeOthers: io.github.meko123456.ridetogether.realtime.InMemoryRealtimeClient?
        get() = client as? io.github.meko123456.ridetogether.realtime.InMemoryRealtimeClient

    /**
     * Whether rides stay on this phone, as they do on the in-memory backend. Only then do the demo
     * affordances belong on screen: on a shared backend the other riders are real, and a pretend
     * one could not be written there anyway.
     */
    val ridesStayOnThisPhone: Boolean get() = fakeOthers != null

    private var roomWatch: Job? = null
    private var positionWatch: Job? = null
    private var logWatch: Job? = null

    /** Every log entry already handed to the session, so that each is ticked exactly once. */
    private val heard = mutableSetOf<RideEvent>()

    private val session = RideSession(selfId = riderId)

    /** Guards against stacking follow-up ticks when several events arrive together. */
    private var followUpScheduled = false

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
            crash.signal.collect { signal ->
                crashSignal = signal
                if (signal == null) return@collect
                // Through the session, so the announcer decides what is spoken — including the
                // countdown, which was silent until a run on a device showed the card appearing
                // and saying nothing. A rider who may have come off cannot read a screen.
                // A confirmed crash also goes into the room's log, so the group's feed shows it. It
                // is not spoken from there; what this phone says comes from the signal itself.
                if (signal is CrashSignal.CrashConfirmed) {
                    room?.let { publish(it.id, RideEvent.PossibleIncident(signal.at, riderId, signal.location)) }
                }
                tickSession(events = emptyList(), crashSignals = listOf(signal))
            }
        }
    }


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
                        notice = if (ridesStayOnThisPhone) {
                            "No ride found for ${code.value}. This build keeps rides on this phone, " +
                                "so only a code made here can be found."
                        } else {
                            "No ride found for ${code.value}."
                        }
                        return@launch
                    }
                    val joined = client.join(
                        roomId = target.id,
                        member = Member(riderId = riderId, displayName = client.selfName),
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
        logWatch?.cancel()
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
        logWatch?.cancel()
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
        heard.clear()
        logWatch = viewModelScope.launch {
            var history = true
            client.observeEvents(roomId).collect { log ->
                onLog(log, history)
                history = false
            }
        }
        // This phone's own fixes go *to* the client and come back through the flow above, which is
        // exactly the path they will take once there is a network in between.
        viewModelScope.launch {
            ownLocation.own.collect { sample ->
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
                    when (val result = client.setState(current.id, transition.to, now)) {
                        // Into the log only once the room has really changed, so no rider is told
                        // the ride is under way when the backend refused to start it.
                        is RealtimeResult.Success ->
                            publish(current.id, RideEvent.StateChanged(now, riderId, transition.from, transition.to))
                        is RealtimeResult.Failure -> notice = describe(result.error)
                    }
                }
                if (transition.to == RoomState.ENDED) {
                    // Nothing should still be talking about a ride that is over.
                    speaker.stop()
                    session.reset()
                    crash.reset()
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
        viewModelScope.launch {
            val result = client.setSweep(
                roomId = current.id,
                riderId = if (alreadySweep) null else targetRiderId,
                now = Clock.System.now(),
            )
            if (result is RealtimeResult.Failure) notice = describe(result.error)
        }
    }

    /**
     * Adds a synthetic rider so the room lifecycle can be exercised on one phone — a ride needs
     * two riders before it can start. Only where [ridesStayOnThisPhone]: anywhere else there are
     * real riders to start one with.
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
        publish(
            roomId = current.id,
            event = RideEvent.Message(Clock.System.now(), riderId, message),
            onSent = { notice = "Sent: ${message.text}" },
            onFailed = { error ->
                notice = if (error == RealtimeError.OFFLINE) "Not sent: no connection." else describe(error)
            },
        )
    }

    /**
     * Stands in for another rider messaging the room, where [ridesStayOnThisPhone]. Exists
     * because the announcer deliberately never reads your own message back to you, so without
     * a second rider there is no way to hear the audio path work at all.
     */
    fun simulateMessageFromAnother(message: QuickMessage) {
        val current = room ?: return
        val other = current.members.firstOrNull { it.riderId != riderId } ?: run {
            notice = "Add a rider first — a message from yourself is not read back to you."
            return
        }
        // Written straight into the in-memory backend, which takes an event in anyone's name, so it
        // arrives the way a real rider's message would: through the log.
        val fakes = fakeOthers ?: return
        viewModelScope.launch {
            fakes.publishEvent(current.id, RideEvent.Message(Clock.System.now(), other.riderId, message))
        }
    }

    /**
     * Adds [event] to the room's log, for every rider. It is not shown or spoken here: it comes back
     * through [onLog] like everyone else's, so this phone and the others see one log in one order.
     */
    private fun publish(
        roomId: String,
        event: RideEvent,
        onSent: () -> Unit = {},
        onFailed: (RealtimeError) -> Unit = {},
    ) {
        viewModelScope.launch {
            when (val result = client.publishEvent(roomId, event)) {
                is RealtimeResult.Success -> onSent()
                is RealtimeResult.Failure -> onFailed(result.error)
            }
        }
    }

    /**
     * The room's log as the client reports it: every rider's events, this phone's included, oldest
     * first. Shown newest first, and each new entry ticked through the session once, which is where
     * another rider's message becomes something said in the headset.
     *
     * Whatever was already there when this phone started following the room is [history]: shown,
     * not read out. Joining a ride should not replay every message sent before you arrived.
     */
    private fun onLog(log: List<RideEvent>, history: Boolean) {
        feed = log.asReversed().take(MAX_FEED)
        val fresh = log.filterNot { it in heard }
        heard += fresh
        if (!history && fresh.isNotEmpty()) tickSession(fresh)
    }

    /**
     * Runs one session tick and speaks whatever comes out.
     *
     * Called after anything that could produce an announcement, rather than on a timer: new
     * positions, new entries in the log, a crash signal. Nothing changes between those, so there is
     * nothing to say. The exception is a held line — the announcer keeps one back while the channel is busy and
     * has no clock of its own, so when it says something is pending we come back once the quiet
     * period has passed. Without that the deferral would wait for whatever event happened to
     * arrive next, which on a quiet ride could be minutes.
     */
    private fun tickSession(
        events: List<RideEvent>,
        crashSignals: List<CrashSignal> = emptyList(),
    ) {
        val current = room ?: return
        val result = session.tick(
            SessionTick(
                now = Clock.System.now(),
                roomState = current.state,
                members = current.members,
                samples = positions,
                batteryPercent = null,
                events = events,
                crashSignals = crashSignals,
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
        crash.cancel(Clock.System.now())
        crashSignal = null
        crash.consumeSignal()
    }

    fun acknowledgeCrash() {
        crashSignal = null
        crash.consumeSignal()
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
        crash.simulateImpact(Clock.System.now())
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

    companion object {
        /**
         * Builds the real thing. The only place the platform implementations are named, so the
         * view model itself stays free of them and the tests can pass fakes.
         */
        fun factory(application: android.app.Application, client: RealtimeClient): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    RideViewModel(
                        client = client,
                        speaker = RideSpeaker(application).also { it.configure() },
                        history = RideHistory(application),
                        ownLocation = RideLocation,
                        crash = CrashMonitor,
                    )
                }
            }


        /** A shade past the announcer's quiet period, so the channel is genuinely free. */
        private const val FOLLOW_UP_DELAY_MS = 21_000L
        private const val MAX_FEED = 50
        private val DEMO_NAMES =
            listOf("Giorgi", "Nika", "Ana", "Luka", "Saba", "Mari", "Dato", "Tazo", "Vato")
    }
}
