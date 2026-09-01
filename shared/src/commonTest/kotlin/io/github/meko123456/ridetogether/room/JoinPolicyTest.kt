package io.github.meko123456.ridetogether.room

import io.github.meko123456.ridetogether.model.JoinCode
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.Room
import io.github.meko123456.ridetogether.model.RoomState
import io.github.meko123456.ridetogether.model.Visibility
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class JoinPolicyTest {

    private val created = Instant.parse("2026-09-01T08:00:00Z")
    private val now = created + 10.minutes

    private fun room(
        state: RoomState = RoomState.LOBBY,
        members: List<Member> = listOf(Member("leader", "Merab")),
        maxRiders: Int = 4,
        autoAccept: Boolean = true,
        visibility: Visibility = Visibility.INVITE_ONLY,
        endedAt: Instant? = null,
    ) = Room(
        id = "room-1",
        code = JoinCode("ABC234"),
        name = "Sunday ride",
        visibility = visibility,
        maxRiders = maxRiders,
        state = state,
        leaderId = "leader",
        members = members,
        autoAcceptJoins = autoAccept,
        createdAt = created,
        endedAt = endedAt,
    )

    @Test
    fun `auto-accept admits a new rider straight away`() {
        assertEquals(JoinOutcome.Admitted, JoinPolicy.evaluate(room(), "newcomer", now))
    }

    @Test
    fun `with auto-accept off the leader must approve`() {
        assertEquals(JoinOutcome.AwaitingApproval, JoinPolicy.evaluate(room(autoAccept = false), "newcomer", now))
    }

    @Test
    fun `an existing member rejoining is recognised - not duplicated`() {
        // Reinstalling the app or recovering from a crash must not create a second rider.
        val r = room(members = listOf(Member("leader", "Merab"), Member("alex", "Alex")))
        assertEquals(JoinOutcome.AlreadyMember, JoinPolicy.evaluate(r, "alex", now))
    }

    @Test
    fun `a full room refuses`() {
        val full = room(members = listOf(Member("a", "A"), Member("b", "B")), maxRiders = 2)
        assertEquals(JoinOutcome.Refused(JoinRefusal.ROOM_FULL), JoinPolicy.evaluate(full, "c", now))
    }

    @Test
    fun `an ended room refuses`() {
        assertEquals(
            JoinOutcome.Refused(JoinRefusal.ROOM_ENDED),
            JoinPolicy.evaluate(room(state = RoomState.ENDED, endedAt = now), "newcomer", now),
        )
    }

    @Test
    fun `an expired room refuses even if it still says LOBBY`() {
        assertEquals(
            JoinOutcome.Refused(JoinRefusal.ROOM_EXPIRED),
            JoinPolicy.evaluate(room(), "newcomer", created + 25.hours),
        )
    }

    @Test
    fun `joining a ride already in progress is allowed`() {
        // Catching the group up at the second fuel stop is normal; refusing just means they
        // ride untracked, which is worse for everyone.
        assertEquals(JoinOutcome.Admitted, JoinPolicy.evaluate(room(state = RoomState.RIDING), "latecomer", now))
        assertEquals(JoinOutcome.Admitted, JoinPolicy.evaluate(room(state = RoomState.PAUSED), "latecomer", now))
    }

    @Test
    fun `membership is checked before anything else can refuse`() {
        // A member of a full room is still a member — they must not be locked out of their own ride.
        val full = room(members = listOf(Member("a", "A"), Member("b", "B")), maxRiders = 2)
        assertEquals(JoinOutcome.AlreadyMember, JoinPolicy.evaluate(full, "b", now))
    }

    @Test
    fun `deep links round-trip`() {
        val r = room()
        assertEquals("ridetogether://join/ABC234", JoinPolicy.deepLink(r))
        assertEquals("ABC234", JoinPolicy.codeFromDeepLink(JoinPolicy.deepLink(r)))
    }

    @Test
    fun `a foreign or malformed link yields no code`() {
        assertNull(JoinPolicy.codeFromDeepLink("https://example.com/join/ABC234"))
        assertNull(JoinPolicy.codeFromDeepLink("ridetogether://join/"))
        assertNull(JoinPolicy.codeFromDeepLink("ridetogether://leave/ABC234"))
    }
}
