package io.github.meko123456.ridetogether.model

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomTest {

    private val now = Instant.parse("2026-09-01T10:00:00Z")

    private fun room(state: RoomState = RoomState.LOBBY, members: List<Member> = emptyList(), maxRiders: Int = 6) = Room(
        id = "room-1",
        code = JoinCode("ABC234"),
        name = "Sunday ride",
        visibility = Visibility.INVITE_ONLY,
        maxRiders = maxRiders,
        state = state,
        leaderId = "leader",
        members = members,
        createdAt = now,
    )

    @Test
    fun `location is shared only while riding or paused`() {
        // This is the privacy promise, so it is asserted in the domain rather than trusted to UI.
        assertFalse(RoomState.LOBBY.sharesLocation)
        assertTrue(RoomState.RIDING.sharesLocation)
        assertTrue(RoomState.PAUSED.sharesLocation)
        assertFalse(RoomState.ENDED.sharesLocation)
    }

    @Test
    fun `fallback alerts are active only while riding`() {
        // Paused means a fuel or food stop: everyone is meant to be spread out and stationary.
        assertTrue(RoomState.RIDING.alertsActive)
        assertFalse(RoomState.PAUSED.alertsActive)
        assertFalse(RoomState.LOBBY.alertsActive)
        assertFalse(RoomState.ENDED.alertsActive)
    }

    @Test
    fun `only leader and co-leader can control the room`() {
        assertTrue(Role.LEADER.canControlRoom)
        assertTrue(Role.CO_LEADER.canControlRoom)
        assertFalse(Role.SWEEP.canControlRoom)
        assertFalse(Role.RIDER.canControlRoom)
    }

    @Test
    fun `rider count is bounded by the spec's 2 to 10`() {
        assertFailsWith<IllegalArgumentException> { room(maxRiders = 1) }
        assertFailsWith<IllegalArgumentException> { room(maxRiders = 11) }
        assertEquals(2, room(maxRiders = 2).maxRiders)
        assertEquals(10, room(maxRiders = 10).maxRiders)
    }

    @Test
    fun `a room reports when it is full`() {
        val members = (1..3).map { Member("r$it", "Rider $it") }
        assertTrue(room(members = members, maxRiders = 3).isFull)
        assertFalse(room(members = members, maxRiders = 4).isFull)
    }

    @Test
    fun `members and roles are looked up by id`() {
        val members = listOf(Member("leader", "Merab", Role.LEADER), Member("r2", "Alex", Role.SWEEP))
        val r = room(members = members)
        assertEquals("Merab", r.member("leader")?.displayName)
        assertEquals(Role.SWEEP, r.roleOf("r2"))
        assertNull(r.member("nobody"))
        assertNull(r.roleOf("nobody"))
    }
}
