package io.github.meko123456.ridetogether.realtime.rtdb

import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.model.FallbackResponse
import io.github.meko123456.ridetogether.model.JoinCode
import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.QuickMessage
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.model.Role
import io.github.meko123456.ridetogether.model.Room
import io.github.meko123456.ridetogether.model.RoomState
import io.github.meko123456.ridetogether.model.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RtdbWireTest {

    private val t0 = Instant.fromEpochMilliseconds(1_756_000_000_000)

    private val leader = Member("leaderUid", "Merab", Role.LEADER)
    private val co = Member("coUid", "Nino", Role.CO_LEADER)
    private val riderB = Member("riderB", "Beka", Role.RIDER, isSweep = true, colorArgb = 0xFF2196F3.toInt(), motorcycle = "Tenere 700")
    private val riderA = Member("riderA", "Ana", Role.RIDER)

    private val room = Room(
        id = "room-1",
        code = JoinCode("7KQ2WX"),
        name = "Sunday run",
        visibility = Visibility.INVITE_ONLY,
        maxRiders = 6,
        state = RoomState.RIDING,
        leaderId = "leaderUid",
        members = listOf(leader, co, riderA, riderB),
        createdAt = t0,
    )

    private fun members(vararg m: Member): JsonObject = buildJsonObject {
        m.forEach { put(it.riderId, RtdbWire.encodeMember(it)) }
    }

    @Test
    fun `paths are the ones the rules guard`() {
        assertEquals("codes/7KQ2WX", RtdbWire.codePath(JoinCode("7KQ2WX")))
        assertEquals("rooms/r/meta", RtdbWire.metaPath("r"))
        assertEquals("rooms/r/members/u", RtdbWire.memberPath("r", "u"))
        assertEquals("positions/r", RtdbWire.positionsPath("r"))
        assertEquals("positions/r/u", RtdbWire.positionPath("r", "u"))
        assertEquals("events/r", RtdbWire.eventsPath("r"))
    }

    @Test
    fun `meta carries exactly the fields the rules validate`() {
        val meta = RtdbWire.encodeMeta(room.copy(state = RoomState.ENDED, endedAt = t0 + 3600.seconds))
        assertEquals(
            setOf("code", "name", "state", "visibility", "maxRiders", "leaderId", "createdAt", "endedAt"),
            meta.keys,
        )
        assertEquals(setOf("code", "name", "state", "visibility", "maxRiders", "leaderId", "createdAt"), RtdbWire.encodeMeta(room).keys)
    }

    @Test
    fun `a room survives the round trip with its members in a stable order`() {
        val decoded = RtdbWire.decodeRoom("room-1", RtdbWire.encodeMeta(room), members(riderB, riderA, leader, co))
        assertEquals(room.copy(members = listOf(leader, co, riderA, riderB)), decoded)
    }

    @Test
    fun `no meta or meta the rules would refuse is no room`() {
        assertNull(RtdbWire.decodeRoom("room-1", null, members(leader)))
        val badState = buildJsonObject {
            RtdbWire.encodeMeta(room).forEach { (k, v) -> if (k != "state") put(k, v) }
            put("state", "SPEEDING")
        }
        assertNull(RtdbWire.decodeRoom("room-1", badState, null))
        val noCode = buildJsonObject { RtdbWire.encodeMeta(room).forEach { (k, v) -> if (k != "code") put(k, v) } }
        assertNull(RtdbWire.decodeRoom("room-1", noCode, null))
    }

    @Test
    fun `a room size the domain cannot hold reads as no room instead of throwing`() {
        // Room's constructor requires 2..10. The rules say the same, but the decoder must not be
        // the place a bad value from the wire turns into a crash.
        val huge = buildJsonObject {
            RtdbWire.encodeMeta(room).forEach { (k, v) -> if (k != "maxRiders") put(k, v) }
            put("maxRiders", 50)
        }
        assertNull(RtdbWire.decodeRoom("room-1", huge, null))
    }

    @Test
    fun `a member row without a valid role is left out rather than guessed`() {
        val raw = Json.parseToJsonElement(
            """{"a":{"displayName":"Ana","role":"RIDER"},"x":{"displayName":"Ghost","role":"ADMIN"},"y":{"role":"RIDER"}}""",
        )
        assertEquals(listOf(Member("a", "Ana", Role.RIDER)), RtdbWire.decodeMembers(raw))
    }

    @Test
    fun `a position keeps its speed and reporting interval`() {
        val sample = RiderSample("riderA", LatLng(41.7151, 44.8271), 12.5f, t0, reportingInterval = 5.seconds)
        val encoded = RtdbWire.encodePosition(sample)
        assertEquals(setOf("lat", "lon", "atMillis", "speedMps", "intervalMillis"), encoded.keys)
        assertEquals(mapOf("riderA" to sample), RtdbWire.decodePositions(buildJsonObject { put("riderA", encoded) }))
    }

    @Test
    fun `a position that is not a usable place is dropped rather than put at zero`() {
        val raw = Json.parseToJsonElement(
            """{"ok":{"lat":41.7,"lon":44.8,"atMillis":1756000000000},
               "noLat":{"lon":44.8,"atMillis":1756000000000},
               "offEarth":{"lat":95.0,"lon":44.8,"atMillis":1756000000000},
               "noTime":{"lat":41.7,"lon":44.8}}""",
        )
        assertEquals(setOf("ok"), RtdbWire.decodePositions(raw).keys)
    }

    @Test
    fun `every kind of event survives the round trip`() {
        val all = listOf(
            RideEvent.Joined(t0, "a"),
            RideEvent.Left(t0, "a"),
            RideEvent.StateChanged(t0, "a", RoomState.LOBBY, RoomState.RIDING),
            RideEvent.FellBehind(t0, "a", 812.5),
            RideEvent.Rejoined(t0, "a"),
            RideEvent.Responded(t0, "a", FallbackResponse.MECHANICAL_ISSUE),
            RideEvent.PossibleIncident(t0, "a", LatLng(41.7, 44.8)),
            RideEvent.PossibleIncident(t0, "a", null),
            RideEvent.SignalLost(t0, "a"),
            RideEvent.SignalRestored(t0, "a"),
            RideEvent.Message(t0, "a", QuickMessage.FUEL_STOP_NEEDED),
            RideEvent.BatterySaver(t0, "a", 14),
        )
        for (event in all) {
            val encoded = RtdbWire.encodeEvent(event)
            assertEquals(true, encoded.keys.containsAll(setOf("type", "at", "riderId")), "required by the rules: $event")
            assertEquals(event, RtdbWire.decodeEvent(encoded))
        }
        assertEquals("Joined", RtdbWire.typeOf(RideEvent.Joined(t0, "a")), "the name the rules tests seed with")
    }

    @Test
    fun `the log is ordered by time and an unknown event type does not lose the rest`() {
        val raw = Json.parseToJsonElement(
            """{"e3":{"type":"Left","at":1756000003000,"riderId":"a"},
               "e1":{"type":"Joined","at":1756000001000,"riderId":"a"},
               "e2":{"type":"Teleported","at":1756000002000,"riderId":"a"},
               "e0":{"type":"Joined","at":1756000001000,"riderId":"b"}}""",
        )
        assertEquals(
            listOf(
                RideEvent.Joined(Instant.fromEpochMilliseconds(1_756_000_001_000), "b"),
                RideEvent.Joined(Instant.fromEpochMilliseconds(1_756_000_001_000), "a"),
                RideEvent.Left(Instant.fromEpochMilliseconds(1_756_000_003_000), "a"),
            ),
            RtdbWire.decodeEvents(raw),
        )
    }
}
