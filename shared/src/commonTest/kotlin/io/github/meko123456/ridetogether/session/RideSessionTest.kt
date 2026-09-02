package io.github.meko123456.ridetogether.session

import io.github.meko123456.ridetogether.alerts.Alert
import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.announce.Priority
import io.github.meko123456.ridetogether.location.LocationPolicy
import io.github.meko123456.ridetogether.model.FallbackResponse
import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.QuickMessage
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.model.RiderStatus
import io.github.meko123456.ridetogether.model.Role
import io.github.meko123456.ridetogether.model.RoomState
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The composition, not the pieces: each of the engine, announcer and policy has its own suite.
 * These are the mistakes only visible once they run together.
 */
class RideSessionTest {

    private val t0 = Instant.parse("2026-09-02T12:00:00Z")
    private val me = "me"
    private val leaderId = "leader"
    private val leader = Member(leaderId, "Merab", Role.LEADER)
    private val self = Member(me, "Alex", Role.RIDER)
    private val nameOf: (String) -> String = { if (it == me) "Alex" else "Merab" }

    private fun east(meters: Double) = LatLng(0.0, meters / 111_320.0)

    private fun sample(id: String, meters: Double, speed: Float, at: Instant) =
        RiderSample(id, east(meters), speed, at)

    private fun tick(
        at: Instant,
        leaderMeters: Double,
        selfMeters: Double,
        selfSpeed: Float = 20f,
        state: RoomState = RoomState.RIDING,
        batteryPercent: Int? = 80,
        events: List<RideEvent> = emptyList(),
    ) = SessionTick(
        now = at,
        roomState = state,
        members = listOf(leader, self),
        samples = mapOf(
            leaderId to sample(leaderId, leaderMeters, 20f, at),
            me to sample(me, selfMeters, selfSpeed, at),
        ),
        batteryPercent = batteryPercent,
        events = events,
    )

    /** Rides normally for long enough that the engine will judge this rider. */
    private fun warmUp(session: RideSession, seconds: Int = 200): Instant {
        var at = t0
        var travelled = 0.0
        repeat(seconds / 5) {
            session.tick(tick(at, travelled + 100.0, travelled), nameOf)
            travelled += 80.0
            at += 5.seconds
        }
        return at
    }

    @Test
    fun `a normal tick reports every rider and asks for the moving interval`() {
        val session = RideSession(selfId = me)
        val result = session.tick(tick(t0, 100.0, 0.0), nameOf)
        assertEquals(2, result.assessments.size)
        assertEquals(LocationPolicy.MOVING_INTERVAL, result.reportingInterval)
        assertTrue(result.announcements.isEmpty(), "nothing to say on an ordinary tick")
    }

    @Test
    fun `the reporting interval follows this rider rather than the group`() {
        // A rider stopped at the back of a group that is still moving should be on the stopped
        // interval. Taking the speed from anyone else keeps their phone awake for nothing.
        val session = RideSession(selfId = me)
        val result = session.tick(tick(t0, 2_000.0, 0.0, selfSpeed = 0f), nameOf)
        assertEquals(LocationPolicy.STOPPED_INTERVAL, result.reportingInterval)
    }

    @Test
    fun `ending the ride tells the platform to stop collecting location`() {
        val session = RideSession(selfId = me)
        val result = session.tick(tick(t0, 100.0, 0.0, state = RoomState.ENDED), nameOf)
        assertNull(result.reportingInterval, "the kill switch has to reach the caller")
    }

    @Test
    fun `a low battery reaches the interval through the session`() {
        val session = RideSession(selfId = me)
        val result = session.tick(tick(t0, 100.0, 0.0, batteryPercent = 8), nameOf)
        assertEquals(LocationPolicy.LOW_BATTERY_MINIMUM_INTERVAL, result.reportingInterval)
    }

    @Test
    fun `my own fall-behind arrives as an alert and an assessment and a spoken line`() {
        // The whole point of the composition: one thing happening produces the three outputs the
        // platform layer needs, and it does not have to correlate them itself.
        val session = RideSession(selfId = me)
        var at = warmUp(session)
        var leaderPos = 3_220.0
        val selfPos = 3_120.0
        var spokenToMe = 0
        var behindAlerts = 0
        repeat(20) {
            val result = session.tick(tick(at, leaderPos, selfPos, selfSpeed = 0f), nameOf)
            behindAlerts += result.alerts.count { it is Alert.FallingBehind }
            spokenToMe += result.announcements.count { it.text.contains("dropped back") }
            leaderPos += 100.0
            at += 5.seconds
        }
        assertEquals(1, behindAlerts, "raised once")
        assertEquals(1, spokenToMe, "and spoken to me once")

        val status = session.tick(tick(at, leaderPos, selfPos, selfSpeed = 0f), nameOf)
            .assessments.single { it.riderId == me }
        assertEquals(RiderStatus.FALLING_BEHIND, status.status)
    }

    @Test
    fun `another rider's fall-behind is never spoken to me even though the alert is reported`() {
        // The engine raises it for the feed and the map; the announcer keeps it off my headset.
        val session = RideSession(selfId = leaderId)
        var at = warmUp(session)
        var leaderPos = 3_220.0
        val selfPos = 3_120.0
        var alerts = 0
        var spoken = 0
        repeat(20) {
            val result = session.tick(tick(at, leaderPos, selfPos, selfSpeed = 0f), nameOf)
            alerts += result.alerts.count { it is Alert.FallingBehind }
            spoken += result.announcements.size
            leaderPos += 100.0
            at += 5.seconds
        }
        assertEquals(1, alerts, "the alert still happens")
        assertEquals(0, spoken, "but I do not hear another rider's private prompt")
    }

    @Test
    fun `an incident is spoken as critical to the rest of the group`() {
        val session = RideSession(selfId = leaderId)
        var at = warmUp(session)
        var leaderPos = 3_220.0
        val selfPos = 3_120.0
        var critical = 0
        repeat(60) {
            val result = session.tick(tick(at, leaderPos, selfPos, selfSpeed = 0f), nameOf)
            critical += result.announcements.count { it.priority == Priority.CRITICAL }
            leaderPos += 100.0
            at += 5.seconds
        }
        assertTrue(critical >= 1, "an unanswered, stationary rider must eventually be announced")
    }

    @Test
    fun `the feed's echo of an alert is not announced a second time`() {
        // Every alert is also written to the room feed. Forwarding both would say it twice.
        val session = RideSession(selfId = leaderId)
        val echoes = listOf(
            RideEvent.PossibleIncident(t0, me, null),
            RideEvent.SignalLost(t0, me),
            RideEvent.FellBehind(t0, me, 1_600.0),
            RideEvent.Rejoined(t0, me),
            RideEvent.Joined(t0, me),
        )
        val result = session.tick(tick(t0, 100.0, 0.0, events = echoes), nameOf)
        assertTrue(result.announcements.isEmpty(), "$result")
    }

    @Test
    fun `a quick message from the feed does reach the headset`() {
        val session = RideSession(selfId = leaderId)
        val result = session.tick(
            tick(t0, 100.0, 0.0, events = listOf(RideEvent.Message(t0, me, QuickMessage.FUEL_STOP_NEEDED))),
            nameOf,
        )
        assertEquals(1, result.announcements.size)
        assertTrue(result.announcements.single().text.contains("fuel"), result.announcements.toString())
    }

    @Test
    fun `answering the prompt stops the escalation`() {
        val session = RideSession(selfId = me)
        var at = warmUp(session)
        var leaderPos = 3_220.0
        val selfPos = 3_120.0
        repeat(20) {
            session.tick(tick(at, leaderPos, selfPos, selfSpeed = 0f), nameOf)
            leaderPos += 100.0
            at += 5.seconds
        }
        session.onResponse(me, FallbackResponse.FINE)

        var incidents = 0
        repeat(60) {
            val result = session.tick(tick(at, leaderPos, selfPos, selfSpeed = 0f), nameOf)
            incidents += result.alerts.count { it is Alert.PossibleIncident }
            leaderPos += 100.0
            at += 5.seconds
        }
        assertEquals(0, incidents, "a rider who said they were fine is not escalated")
    }

    @Test
    fun `a rider who leaves is forgotten rather than judged for being absent`() {
        val session = RideSession(selfId = leaderId)
        var at = warmUp(session)
        session.onRiderLeft(me)
        // They are gone from the room, so they are gone from the tick too.
        val result = session.tick(
            SessionTick(
                now = at,
                roomState = RoomState.RIDING,
                members = listOf(leader),
                samples = mapOf(leaderId to sample(leaderId, 3_220.0, 20f, at)),
            ),
            nameOf,
        )
        assertEquals(1, result.assessments.size)
        assertTrue(result.alerts.isEmpty(), "no alerts about someone who left: ${result.alerts}")
    }
}
