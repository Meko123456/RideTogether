package io.github.meko123456.ridetogether.realtime

import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.model.JoinCode
import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.QuickMessage
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.model.Role
import io.github.meko123456.ridetogether.model.Room
import io.github.meko123456.ridetogether.model.RoomState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * These are as much a specification of [RealtimeClient] as tests of this implementation: whatever
 * backs it later has to behave the same way, and this is where "the same way" is written down.
 */
class InMemoryRealtimeClientTest {

    private val t0 = Instant.parse("2026-09-03T09:00:00Z")
    private val code = JoinCode("ABC234")
    private val me = "me"
    private val other = "nika"

    private fun client() = InMemoryRealtimeClient(selfId = me)

    private fun sample(id: String, at: Instant) =
        RiderSample(id, LatLng(41.7, 44.8), 20f, at)

    @Test
    fun `a created room has its creator as leader and only member`() = runTest {
        val room = client().createRoom("Sunday ride", code, t0).valueOrNull
        assertTrue(room != null)
        assertEquals(me, room.leaderId)
        assertEquals(listOf(me), room.members.map { it.riderId })
        assertEquals(RoomState.LOBBY, room.state)
    }

    @Test
    fun `the creator can actually control the ride they created`() = runTest {
        // Regression. leaderId was set but the *member* carried the default RIDER role, and every
        // permission check goes through the member's role — so the state machine refused to let a
        // rider start their own ride with NOT_PERMITTED, which was the correct answer to the wrong
        // data. Only visible by running the app: the room simply stayed in the lobby.
        val room = requireNotNull(client().createRoom("Ride", code, t0).valueOrNull)
        assertEquals(Role.LEADER, room.roleOf(me))
        assertTrue(room.roleOf(me)?.canControlRoom == true)
    }

    @Test
    fun `a code already in use is reported rather than silently overwriting a ride`() = runTest {
        val client = client()
        client.createRoom("First", code, t0)
        val second = client.createRoom("Second", code, t0)
        assertEquals(RealtimeError.CODE_TAKEN, second.errorOrNull)
        // And the first ride is untouched, which is the point.
        assertEquals("First", client.room(code.value).value?.name)
    }

    @Test
    fun `a code nobody used resolves to no room rather than an error`() = runTest {
        val result = client().findRoom(JoinCode("ZZZZZZ"))
        assertNull(result.valueOrNull, "absent is a null room")
        assertNull(result.errorOrNull, "and not a failure")
    }

    @Test
    fun `joining applies the same policy the backend rules will`() = runTest {
        val client = client()
        client.createRoom("Ride", code, t0)
        val joined = client.join(code.value, Member(other, "Nika"), t0)
        assertEquals(2, joined.valueOrNull?.members?.size)
    }

    @Test
    fun `joining a full room is refused`() = runTest {
        val client = client()
        client.createRoom("Ride", code, t0)
        repeat(Room.MAX_RIDERS - 1) { index ->
            client.join(code.value, Member("rider-$index", "R$index"), t0)
        }
        val overflow = client.join(code.value, Member("one-too-many", "X"), t0)
        assertEquals(RealtimeError.NOT_PERMITTED, overflow.errorOrNull)
    }

    @Test
    fun `joining an expired room reports the room is gone`() = runTest {
        val client = client()
        client.createRoom("Ride", code, t0)
        val late = client.join(code.value, Member(other, "Nika"), t0 + 25.hours)
        assertEquals(RealtimeError.ROOM_GONE, late.errorOrNull)
    }

    @Test
    fun `an expired room cannot be driven even by its leader`() = runTest {
        val client = client()
        client.createRoom("Ride", code, t0)
        val result = client.setState(code.value, RoomState.RIDING, t0 + 25.hours)
        assertEquals(RealtimeError.ROOM_GONE, result.errorOrNull)
    }

    @Test
    fun `naming a sweep is room state so every rider agrees who is last`() = runTest {
        // It changes what the alert engine believes, so a local-only flag would leave one phone
        // quiet about the back marker while another kept prompting them.
        val client = client()
        client.createRoom("Ride", code, t0)
        client.join(code.value, Member(other, "Nika"), t0)

        client.setSweep(code.value, other, t0)
        assertTrue(client.room(code.value).value?.sweep?.riderId == other)
    }

    @Test
    fun `there is only ever one sweep`() = runTest {
        // Two back markers means two riders the engine never prompts, which is the one thing
        // this flag must not be able to do.
        val client = client()
        client.createRoom("Ride", code, t0)
        client.join(code.value, Member(other, "Nika"), t0)

        client.setSweep(code.value, other, t0)
        client.setSweep(code.value, me, t0)
        val members = client.room(code.value).value?.members.orEmpty()
        assertEquals(1, members.count { it.isSweep })
        assertEquals(me, members.single { it.isSweep }.riderId)
    }

    @Test
    fun `the sweep can be cleared`() = runTest {
        val client = client()
        client.createRoom("Ride", code, t0)
        client.setSweep(code.value, me, t0)
        client.setSweep(code.value, null, t0)
        assertNull(client.room(code.value).value?.sweep)
    }

    @Test
    fun `somebody who is not in the room cannot be made sweep`() = runTest {
        val client = client()
        client.createRoom("Ride", code, t0)
        val result = client.setSweep(code.value, "stranger", t0)
        assertEquals(RealtimeError.NOT_PERMITTED, result.errorOrNull)
        assertNull(client.room(code.value).value?.sweep)
    }

    @Test
    fun `a rider who leaves stops appearing on the map`() = runTest {
        // Presence going away has to take the position with it, or a rider who left lingers at
        // their last known spot and the group keeps waiting for them.
        val client = client()
        client.createRoom("Ride", code, t0)
        client.publishPosition(code.value, sample(me, t0))
        assertEquals(setOf(me), client.visibleRiders(code.value).first())

        client.leave(code.value, t0)
        assertEquals(emptySet(), client.visibleRiders(code.value).first())
        assertTrue(client.room(code.value).value?.members?.none { it.riderId == me } == true)
    }

    @Test
    fun `positions from everyone arrive keyed by rider and ready for the engine`() = runTest {
        val client = client()
        client.createRoom("Ride", code, t0)
        client.publishPosition(code.value, sample(me, t0))
        client.receivePosition(code.value, sample(other, t0))
        val positions = client.observePositions(code.value).first()
        assertEquals(setOf(me, other), positions.keys)
        assertEquals(20f, positions[other]?.speedMps)
    }

    @Test
    fun `the ride log keeps its order`() = runTest {
        val client = client()
        client.createRoom("Ride", code, t0)
        client.publishEvent(code.value, RideEvent.Joined(t0, me))
        client.publishEvent(code.value, RideEvent.Message(t0 + 1.hours, other, QuickMessage.SLOW_DOWN))
        val log = client.observeEvents(code.value).first()
        assertEquals(2, log.size)
        assertTrue(log.first() is RideEvent.Joined, "oldest first")
    }

    @Test
    fun `going offline fails calls as offline rather than throwing`() = runTest {
        // A dropped connection mid-ride is normal, so it is a value the UI can say something
        // specific about, not an exception thrown past the caller.
        val client = client()
        client.createRoom("Ride", code, t0)
        client.setConnected(false)

        assertEquals(RealtimeError.OFFLINE, client.publishPosition(code.value, sample(me, t0)).errorOrNull)
        assertEquals(RealtimeError.OFFLINE, client.findRoom(code).errorOrNull)
        assertEquals(RealtimeError.OFFLINE, client.setState(code.value, RoomState.RIDING, t0).errorOrNull)
        assertEquals(false, client.connected.first())
    }

    @Test
    fun `a room that disappears under the app is reported as gone`() = runTest {
        // Expiry cleanup, or a ride ended by someone else. The app has to survive it.
        val client = client()
        client.createRoom("Ride", code, t0)
        client.dropRoom(code.value)
        assertEquals(RealtimeError.ROOM_GONE, client.setState(code.value, RoomState.RIDING, t0).errorOrNull)
        assertEquals(RealtimeError.ROOM_GONE, client.publishPosition(code.value, sample(me, t0)).errorOrNull)
        assertNull(client.observeRoom(code.value).first())
    }

    @Test
    fun `what went to the wire is inspectable so a test can check the interval survived`() = runTest {
        // The alert engine's staleness detection scales with each rider's reporting interval, so
        // losing it in transit would silently break signal-loss detection.
        val client = client()
        client.createRoom("Ride", code, t0)
        client.publishPosition(
            code.value,
            RiderSample(me, LatLng(41.7, 44.8), 20f, t0, reportingInterval = 30.hours),
        )
        assertEquals(30.hours, client.published.single().second.reportingInterval)
    }
}
