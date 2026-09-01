package io.github.meko123456.ridetogether.room

import io.github.meko123456.ridetogether.model.Role
import io.github.meko123456.ridetogether.model.RoomState
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class RoomStateMachineTest {

    private val created = Instant.parse("2026-09-01T08:00:00Z")
    private val now = created + 30.minutes

    private fun decide(
        state: RoomState,
        command: RoomCommand,
        role: Role? = Role.LEADER,
        riders: Int = 4,
        endedAt: Instant? = null,
        at: Instant = now,
    ) = RoomStateMachine.decide(state, role, command, riders, created, endedAt, at)

    @Test
    fun `the leader can start, pause, resume and end`() {
        assertEquals(RoomTransition.Accepted(RoomState.LOBBY, RoomState.RIDING), decide(RoomState.LOBBY, RoomCommand.StartRide))
        assertEquals(RoomTransition.Accepted(RoomState.RIDING, RoomState.PAUSED), decide(RoomState.RIDING, RoomCommand.PauseRide))
        assertEquals(RoomTransition.Accepted(RoomState.PAUSED, RoomState.RIDING), decide(RoomState.PAUSED, RoomCommand.ResumeRide))
        assertEquals(RoomTransition.Accepted(RoomState.RIDING, RoomState.ENDED), decide(RoomState.RIDING, RoomCommand.EndRide))
    }

    @Test
    fun `a co-leader has the same control as the leader`() {
        assertTrue(decide(RoomState.RIDING, RoomCommand.PauseRide, role = Role.CO_LEADER) is RoomTransition.Accepted)
    }

    @Test
    fun `an ordinary rider cannot control the room`() {
        assertEquals(
            RoomTransition.Rejected(RoomRejection.NOT_PERMITTED),
            decide(RoomState.RIDING, RoomCommand.EndRide, role = Role.RIDER),
        )
    }

    @Test
    fun `a non-member cannot control the room`() {
        assertEquals(
            RoomTransition.Rejected(RoomRejection.NOT_PERMITTED),
            decide(RoomState.RIDING, RoomCommand.PauseRide, role = null),
        )
    }

    @Test
    fun `ENDED is terminal`() {
        for (command in listOf(RoomCommand.StartRide, RoomCommand.PauseRide, RoomCommand.ResumeRide, RoomCommand.EndRide)) {
            assertEquals(
                RoomTransition.Rejected(RoomRejection.ILLEGAL_TRANSITION),
                decide(RoomState.ENDED, command),
                "ENDED should refuse $command",
            )
        }
    }

    @Test
    fun `you cannot pause a ride that has not started or resume one that is running`() {
        assertEquals(
            RoomTransition.Rejected(RoomRejection.ILLEGAL_TRANSITION),
            decide(RoomState.LOBBY, RoomCommand.PauseRide),
        )
        assertEquals(
            RoomTransition.Rejected(RoomRejection.ILLEGAL_TRANSITION),
            decide(RoomState.RIDING, RoomCommand.ResumeRide),
        )
    }

    @Test
    fun `a ride needs at least two riders`() {
        // The entire feature set is about the gap between riders, so one rider is not a ride.
        assertEquals(
            RoomTransition.Rejected(RoomRejection.NOT_ENOUGH_RIDERS),
            decide(RoomState.LOBBY, RoomCommand.StartRide, riders = 1),
        )
        assertTrue(decide(RoomState.LOBBY, RoomCommand.StartRide, riders = 2) is RoomTransition.Accepted)
    }

    @Test
    fun `a room expires 24 hours after creation and refuses everything`() {
        val late = created + 24.hours
        assertEquals(
            RoomTransition.Rejected(RoomRejection.ROOM_EXPIRED),
            decide(RoomState.RIDING, RoomCommand.PauseRide, at = late),
        )
        assertTrue(RoomStateMachine.isExpired(RoomState.RIDING, created, null, late))
        assertFalse(RoomStateMachine.isExpired(RoomState.RIDING, created, null, created + 23.hours))
    }

    @Test
    fun `an ended room lingers an hour for the summary, then expires`() {
        val endedAt = created + 2.hours
        assertFalse(RoomStateMachine.isExpired(RoomState.ENDED, created, endedAt, endedAt + 59.minutes))
        assertTrue(RoomStateMachine.isExpired(RoomState.ENDED, created, endedAt, endedAt + 1.hours))
    }

    @Test
    fun `expiry outranks permission so a stale client cannot act on a dead room`() {
        // A client with a cached room must refuse, whatever it thinks its role is.
        val late = created + 25.hours
        assertEquals(
            RoomTransition.Rejected(RoomRejection.ROOM_EXPIRED),
            decide(RoomState.RIDING, RoomCommand.EndRide, role = Role.RIDER, at = late),
        )
    }

    @Test
    fun `the transition table has no path back out of ENDED`() {
        assertFalse(RoomStateMachine.canTransition(RoomState.ENDED, RoomState.RIDING))
        assertFalse(RoomStateMachine.canTransition(RoomState.ENDED, RoomState.LOBBY))
        assertTrue(RoomStateMachine.canTransition(RoomState.LOBBY, RoomState.ENDED))
    }
}
