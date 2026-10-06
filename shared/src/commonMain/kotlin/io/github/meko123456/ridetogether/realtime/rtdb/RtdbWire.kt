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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The shape RideTogether's data takes in the Realtime Database, in both directions.
 *
 * `database.rules.json` is the schema. Every path and field name here is one the rules know, and
 * the tests pin them, because a renamed field does not fail to compile: it fails at runtime, in
 * the middle of a ride, as data the rules refuse or the other riders' phones cannot read.
 *
 * Decoding is strict where a missing field would mean acting on nonsense, so a position without
 * coordinates is dropped rather than placed at 0,0, and tolerant where the wire can legitimately
 * hold something this build does not know: an event type added by a newer app is skipped, not a
 * reason to lose the whole log.
 */
internal object RtdbWire {

    // ─────────────────────────────── paths

    fun codePath(code: JoinCode): String = "codes/${code.value}"
    fun roomPath(roomId: String): String = "rooms/$roomId"
    fun metaPath(roomId: String): String = "rooms/$roomId/meta"
    fun memberPath(roomId: String, riderId: String): String = "rooms/$roomId/members/$riderId"
    fun positionsPath(roomId: String): String = "positions/$roomId"
    fun positionPath(roomId: String, riderId: String): String = "positions/$roomId/$riderId"
    fun eventsPath(roomId: String): String = "events/$roomId"

    // ─────────────────────────────── rooms/$roomId/meta

    /**
     * The room's own details. Only the fields the rules validate: the route, the meeting point and
     * auto-accept are not part of the shared room yet, so a reader gets the domain's defaults.
     */
    fun encodeMeta(room: Room): JsonObject = buildJsonObject {
        put("code", room.code.value)
        put("name", room.name)
        put("state", room.state.name)
        put("visibility", room.visibility.name)
        put("maxRiders", room.maxRiders)
        put("leaderId", room.leaderId)
        put("createdAt", room.createdAt.toEpochMilliseconds())
        room.endedAt?.let { put("endedAt", it.toEpochMilliseconds()) }
    }

    /**
     * The room, from its meta and its members. Null when there is no meta, or meta the rules would
     * never have let in: a room that cannot be read in full is treated as gone, not half-shown.
     */
    fun decodeRoom(roomId: String, meta: JsonElement?, members: JsonElement?): Room? {
        val m = meta as? JsonObject ?: return null
        val code = m.string("code")?.takeIf(JoinCode::isValid)?.let(::JoinCode) ?: return null
        val name = m.string("name") ?: return null
        val state = m.string("state")?.let { enumOrNull<RoomState>(it) } ?: return null
        // Room's constructor throws outside this range, so it is checked here, not trusted.
        val maxRiders = m.int("maxRiders")?.takeIf { it in Room.MIN_RIDERS..Room.MAX_RIDERS } ?: return null
        val leaderId = m.string("leaderId") ?: return null
        val createdAt = m.long("createdAt")?.let(Instant::fromEpochMilliseconds) ?: return null
        return Room(
            id = roomId,
            code = code,
            name = name,
            visibility = m.string("visibility")?.let { enumOrNull<Visibility>(it) } ?: Visibility.INVITE_ONLY,
            maxRiders = maxRiders,
            state = state,
            leaderId = leaderId,
            members = decodeMembers(members),
            createdAt = createdAt,
            endedAt = m.long("endedAt")?.let(Instant::fromEpochMilliseconds),
        )
    }

    // ─────────────────────────────── rooms/$roomId/members/$riderId

    fun encodeMember(member: Member): JsonObject = buildJsonObject {
        put("displayName", member.displayName)
        put("role", member.role.name)
        put("isSweep", member.isSweep)
        member.colorArgb?.let { put("colorArgb", it) }
        member.motorcycle?.let { put("motorcycle", it) }
    }

    /**
     * Members in a stable order: leader, then co-leaders, then riders, each by name. The database
     * keeps no join order, and a list that reshuffled on every update would make the lobby jump.
     */
    fun decodeMembers(members: JsonElement?): List<Member> {
        val byId = members as? JsonObject ?: return emptyList()
        return byId.mapNotNull { (riderId, value) ->
            val m = value as? JsonObject ?: return@mapNotNull null
            Member(
                riderId = riderId,
                displayName = m.string("displayName") ?: return@mapNotNull null,
                role = m.string("role")?.let { enumOrNull<Role>(it) } ?: return@mapNotNull null,
                isSweep = m.boolean("isSweep") ?: false,
                colorArgb = m.int("colorArgb"),
                motorcycle = m.string("motorcycle"),
            )
        }.sortedWith(compareBy<Member>({ it.role.ordinal }, { it.displayName }, { it.riderId }))
    }

    // ─────────────────────────────── positions/$roomId/$riderId

    /**
     * A position as it goes on the wire. The reporting interval goes with it: staleness is judged
     * against how often a rider is reporting, so a sample without it would make every other phone
     * guess.
     */
    fun encodePosition(sample: RiderSample): JsonObject = buildJsonObject {
        put("lat", sample.location.latitude)
        put("lon", sample.location.longitude)
        put("atMillis", sample.at.toEpochMilliseconds())
        sample.speedMps?.let { put("speedMps", it.toDouble()) }
        sample.reportingInterval?.let { put("intervalMillis", it.inWholeMilliseconds) }
    }

    /** Everyone's latest position, keyed by rider. A sample that is not a usable position is left out. */
    fun decodePositions(positions: JsonElement?): Map<String, RiderSample> {
        val byId = positions as? JsonObject ?: return emptyMap()
        return byId.mapNotNull { (riderId, value) ->
            val p = value as? JsonObject ?: return@mapNotNull null
            val location = latLngOrNull(p.double("lat"), p.double("lon")) ?: return@mapNotNull null
            val at = p.long("atMillis")?.let(Instant::fromEpochMilliseconds) ?: return@mapNotNull null
            riderId to RiderSample(
                riderId = riderId,
                location = location,
                speedMps = p.double("speedMps")?.toFloat(),
                at = at,
                reportingInterval = p.long("intervalMillis")?.takeIf { it > 0 }?.milliseconds,
            )
        }.toMap()
    }

    // ─────────────────────────────── events/$roomId/$eventId

    /** An event with its `type`, `at` and `riderId`, which the rules require, and its own fields. */
    fun encodeEvent(event: RideEvent): JsonObject = buildJsonObject {
        put("type", typeOf(event))
        put("at", event.at.toEpochMilliseconds())
        put("riderId", event.riderId)
        when (event) {
            is RideEvent.StateChanged -> {
                put("from", event.from.name)
                put("to", event.to.name)
            }
            is RideEvent.FellBehind -> put("gapMeters", event.gapMeters)
            is RideEvent.Responded -> put("response", event.response.name)
            is RideEvent.PossibleIncident -> event.location?.let { location ->
                put("lat", location.latitude)
                put("lon", location.longitude)
            }
            is RideEvent.Message -> put("message", event.message.name)
            is RideEvent.BatterySaver -> put("batteryPercent", event.batteryPercent)
            is RideEvent.Joined, is RideEvent.Left, is RideEvent.Rejoined,
            is RideEvent.SignalLost, is RideEvent.SignalRestored -> Unit
        }
    }

    /** The log, oldest first, ties broken by event id so every phone shows the same order. */
    fun decodeEvents(events: JsonElement?): List<RideEvent> {
        val byId = events as? JsonObject ?: return emptyList()
        return byId.entries
            .mapNotNull { (eventId, value) -> decodeEvent(value)?.let { eventId to it } }
            .sortedWith(compareBy({ it.second.at }, { it.first }))
            .map { it.second }
    }

    fun decodeEvent(value: JsonElement?): RideEvent? {
        val e = value as? JsonObject ?: return null
        val at = e.long("at")?.let(Instant::fromEpochMilliseconds) ?: return null
        val riderId = e.string("riderId") ?: return null
        return when (e.string("type")) {
            "Joined" -> RideEvent.Joined(at, riderId)
            "Left" -> RideEvent.Left(at, riderId)
            "StateChanged" -> RideEvent.StateChanged(
                at = at,
                riderId = riderId,
                from = e.string("from")?.let { enumOrNull<RoomState>(it) } ?: return null,
                to = e.string("to")?.let { enumOrNull<RoomState>(it) } ?: return null,
            )
            "FellBehind" -> RideEvent.FellBehind(at, riderId, e.double("gapMeters") ?: return null)
            "Rejoined" -> RideEvent.Rejoined(at, riderId)
            "Responded" -> RideEvent.Responded(
                at = at,
                riderId = riderId,
                response = e.string("response")?.let { enumOrNull<FallbackResponse>(it) } ?: return null,
            )
            "PossibleIncident" -> RideEvent.PossibleIncident(at, riderId, latLngOrNull(e.double("lat"), e.double("lon")))
            "SignalLost" -> RideEvent.SignalLost(at, riderId)
            "SignalRestored" -> RideEvent.SignalRestored(at, riderId)
            "Message" -> RideEvent.Message(
                at = at,
                riderId = riderId,
                message = e.string("message")?.let { enumOrNull<QuickMessage>(it) } ?: return null,
            )
            "BatterySaver" -> RideEvent.BatterySaver(at, riderId, e.int("batteryPercent") ?: return null)
            else -> null
        }
    }

    /** The wire name of an event type: the class name, which is what the rules tests seed with too. */
    fun typeOf(event: RideEvent): String = when (event) {
        is RideEvent.Joined -> "Joined"
        is RideEvent.Left -> "Left"
        is RideEvent.StateChanged -> "StateChanged"
        is RideEvent.FellBehind -> "FellBehind"
        is RideEvent.Rejoined -> "Rejoined"
        is RideEvent.Responded -> "Responded"
        is RideEvent.PossibleIncident -> "PossibleIncident"
        is RideEvent.SignalLost -> "SignalLost"
        is RideEvent.SignalRestored -> "SignalRestored"
        is RideEvent.Message -> "Message"
        is RideEvent.BatterySaver -> "BatterySaver"
    }

    // ─────────────────────────────── helpers

    private fun JsonObject.primitive(key: String): JsonPrimitive? =
        (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String): Long? = primitive(key)?.takeUnless { it.isString }?.let { p ->
        p.longOrNull ?: p.doubleOrNull?.takeIf { it == kotlin.math.floor(it) }?.toLong()
    }
    private fun JsonObject.int(key: String): Int? = primitive(key)?.takeUnless { it.isString }?.intOrNull
    private fun JsonObject.double(key: String): Double? = primitive(key)?.takeUnless { it.isString }?.doubleOrNull
    private fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.takeUnless { it.isString }?.booleanOrNull

    private fun latLngOrNull(lat: Double?, lon: Double?): LatLng? =
        if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) LatLng(lat, lon) else null

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
        enumValues<E>().firstOrNull { it.name == name }
}
