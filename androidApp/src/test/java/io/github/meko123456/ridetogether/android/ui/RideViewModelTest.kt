package io.github.meko123456.ridetogether.android.ui

import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.android.history.RideHistory
import io.github.meko123456.ridetogether.announce.Announcement
import io.github.meko123456.ridetogether.crash.CrashSignal
import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.model.QuickMessage
import io.github.meko123456.ridetogether.model.RoomState
import io.github.meko123456.ridetogether.realtime.InMemoryRealtimeClient
import io.github.meko123456.ridetogether.room.RoomCommand
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private class FakeVoice : Voice {
    val spoken = mutableListOf<Announcement>()
    var stopped = 0
    override fun speak(announcements: List<Announcement>) { spoken += announcements }
    override fun stop() { stopped++ }
    override fun release() = Unit
    override fun status(): String = "Voice ready"
}

private class FakeLocation : OwnLocation {
    private val _own = MutableStateFlow<RiderSample?>(null)
    override val own: StateFlow<RiderSample?> = _own
    fun emit(sample: RiderSample) { _own.value = sample }
}

private class FakeCrash : CrashDetection {
    private val _signal = MutableStateFlow<CrashSignal?>(null)
    override val signal: StateFlow<CrashSignal?> = _signal
    var cancelled = 0
    var resets = 0
    override fun cancel(at: Instant) { cancelled++ }
    override fun consumeSignal() { _signal.value = null }
    override fun reset() { resets++ }
    override fun simulateImpact(now: Instant) = Unit
    fun emit(signal: CrashSignal) { _signal.value = signal }
}

/**
 * The wiring layer, which had no tests until a bug here — a rider being refused permission to
 * start the ride they had just created — was found by running the app rather than by CI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val directory: File = Files.createTempDirectory("vm-history").toFile()

    private val client = InMemoryRealtimeClient(selfId = "me")
    private val voice = FakeVoice()
    private val location = FakeLocation()
    private val crash = FakeCrash()

    private fun viewModel() = RideViewModel(
        client = client,
        speaker = voice,
        history = RideHistory(File(directory, "history.json")),
        ownLocation = location,
        crash = crash,
        riderId = "me",
    )

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    private fun sample(at: Instant, meters: Double, speed: Float) =
        RiderSample("me", LatLng(0.0, meters / 111_320.0), speed, at)

    @Test
    fun `creating a ride makes the creator its leader and lets them start it`() = runTest(dispatcher) {
        // The regression. leaderId was set while the *member* carried the default RIDER role, so
        // the state machine refused with NOT_PERMITTED and the room simply stayed in the lobby —
        // no crash, no error, nothing to see except that the button did nothing.
        val vm = viewModel()
        vm.createRide()
        advanceUntilIdle()
        assertTrue(vm.room != null, "a ride should exist")

        vm.addDemoRider()
        advanceUntilIdle()
        vm.send(RoomCommand.StartRide)
        advanceUntilIdle()

        assertEquals(RoomState.RIDING, vm.room?.state)
        assertNull(vm.notice, "no rejection: ${vm.notice}")
    }

    @Test
    fun `starting a ride alone is refused with a reason rather than silently`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.createRide()
        advanceUntilIdle()
        vm.send(RoomCommand.StartRide)
        advanceUntilIdle()

        assertEquals(RoomState.LOBBY, vm.room?.state)
        assertTrue(vm.notice?.contains("2 riders") == true, "was ${vm.notice}")
    }

    @Test
    fun `positions published by this phone come back through the client`() = runTest(dispatcher) {
        // The round trip that will one day cross a network: the service publishes, the client
        // reports, and the engine is fed from the client rather than from the sensor directly.
        val vm = viewModel()
        vm.createRide(); advanceUntilIdle()
        vm.addDemoRider(); advanceUntilIdle()
        vm.send(RoomCommand.StartRide); advanceUntilIdle()

        location.emit(sample(Clock.System.now(), 0.0, 20f))
        advanceUntilIdle()

        assertTrue(vm.positions.containsKey("me"), "own position should arrive: ${vm.positions}")
        assertTrue(vm.assessments.any { it.riderId == "me" }, "and reach the engine")
    }

    @Test
    fun `nothing is published before the ride starts`() = runTest(dispatcher) {
        // The privacy promise, at this layer: a lobby is not a ride.
        val vm = viewModel()
        vm.createRide(); advanceUntilIdle()
        location.emit(sample(Clock.System.now(), 0.0, 20f))
        advanceUntilIdle()
        assertTrue(vm.positions.isEmpty(), "${vm.positions}")
    }

    @Test
    fun `ending a ride stops the voice, resets the detector and stores a summary`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.createRide(); advanceUntilIdle()
        vm.addDemoRider(); advanceUntilIdle()
        vm.send(RoomCommand.StartRide); advanceUntilIdle()

        var at = Clock.System.now()
        var metres = 0.0
        repeat(30) {
            location.emit(sample(at, metres, 20f))
            advanceUntilIdle()
            metres += 20.0
            at += 1.seconds
        }

        vm.send(RoomCommand.EndRide)
        advanceUntilIdle()

        assertEquals(RoomState.ENDED, vm.room?.state)
        assertTrue(voice.stopped >= 1, "a finished ride should not still be being talked about")
        assertTrue(crash.resets >= 1, "a finished ride cannot be crashed out of")
        assertTrue(vm.lastSummary != null, "a summary should be produced")
        assertTrue(vm.rides.isNotEmpty(), "and stored")
    }

    @Test
    fun `a crash countdown reaches the speaker`() = runTest(dispatcher) {
        // The gap a device found: the countdown was drawn and never spoken.
        val vm = viewModel()
        vm.createRide(); advanceUntilIdle()
        vm.addDemoRider(); advanceUntilIdle()
        vm.send(RoomCommand.StartRide); advanceUntilIdle()

        val now = Clock.System.now()
        crash.emit(CrashSignal.CountdownStarted(at = now, expiresAt = now + 30.seconds))
        advanceUntilIdle()

        assertTrue(vm.crashSignal is CrashSignal.CountdownStarted)
        assertTrue(
            voice.spoken.any { it.text.contains("come off") },
            "the countdown must be spoken: ${voice.spoken.map { it.text }}",
        )
    }

    @Test
    fun `saying I'm fine cancels the countdown`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.createRide(); advanceUntilIdle()
        val now = Clock.System.now()
        crash.emit(CrashSignal.CountdownStarted(at = now, expiresAt = now + 30.seconds))
        advanceUntilIdle()

        vm.cancelCrashCountdown()
        advanceUntilIdle()
        assertEquals(1, crash.cancelled)
        assertNull(vm.crashSignal)
    }

    @Test
    fun `a message from another rider is spoken but my own is not`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.createRide(); advanceUntilIdle()
        vm.addDemoRider(); advanceUntilIdle()
        vm.send(RoomCommand.StartRide); advanceUntilIdle()
        voice.spoken.clear()

        vm.send(QuickMessage.FUEL_STOP_NEEDED)
        advanceUntilIdle()
        assertTrue(voice.spoken.isEmpty(), "my own message is not read back: ${voice.spoken}")
        assertTrue(vm.feed.isNotEmpty(), "but it is in the feed")

        vm.simulateMessageFromAnother(QuickMessage.SLOW_DOWN)
        advanceUntilIdle()
        assertTrue(
            voice.spoken.any { it.text.contains("slow down") },
            "someone else's is spoken: ${voice.spoken.map { it.text }}",
        )
    }

    @Test
    fun `leaving a room clears the ride rather than leaving it half-open`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.createRide(); advanceUntilIdle()
        vm.addDemoRider(); advanceUntilIdle()
        vm.send(RoomCommand.StartRide); advanceUntilIdle()

        vm.leaveRoom()
        advanceUntilIdle()
        assertNull(vm.room)
        assertTrue(vm.feed.isEmpty(), "the previous ride's log should not follow you out")
    }
}
