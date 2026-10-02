package io.github.meko123456.ridetogether.realtime.rtdb

import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.model.JoinCode
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.model.Role
import io.github.meko123456.ridetogether.model.Room
import io.github.meko123456.ridetogether.model.RoomState
import io.github.meko123456.ridetogether.model.Visibility
import io.github.meko123456.ridetogether.realtime.RealtimeClient
import io.github.meko123456.ridetogether.realtime.RealtimeError
import io.github.meko123456.ridetogether.realtime.RealtimeResult
import io.github.meko123456.ridetogether.realtime.asRealtimeSuccess
import io.github.meko123456.ridetogether.realtime.realtimeFailure
import io.github.meko123456.ridetogether.room.RoomStateMachine
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Which Realtime Database to talk to.
 *
 * [url] is the instance's own address, `https://<instance>.firebasedatabase.app` for a real
 * project, or `http://127.0.0.1:9110` for the emulator. The emulator serves every instance from one
 * address, so it needs the instance's name as [namespace]; a real instance's address already is one.
 */
data class RtdbDatabase(val url: String, val namespace: String? = null)

/** Who the client is, to the database: a Firebase ID token for the signed-in rider, or null for nobody. */
fun interface RtdbCredentials {
    suspend fun idToken(): String?
}

/**
 * [RealtimeClient] on the Firebase Realtime Database, over its REST and streaming API (#10).
 *
 * REST rather than Firebase's Android and iOS SDKs, so the one implementation in `commonMain` serves
 * both apps, and so Firebase stays the swappable part the interface promised: nothing here leaks a
 * Firebase type past this file.
 *
 * The layout and the permissions are `database.rules.json`'s, and a few things follow from them:
 *
 * - **Creating a room is two writes.** Meta first, naming this client leader, then this client's own
 *   member row, because the rule that lets a creator write a LEADER row checks meta that already
 *   exists. The code is claimed before either, write-once, so losing a race to another creator reads
 *   as [RealtimeError.CODE_TAKEN].
 * - **Who is in a room is members-only.** [findRoom] returns a room with no members to anyone not
 *   in it, and whether a room is full can only be known from inside: [join] writes this rider in,
 *   reads the members it can now see, and leaves again with [RealtimeError.NOT_PERMITTED] if that
 *   took the room past its size. Two riders taking the last place at the same moment may both back
 *   out; neither ends up in a room over capacity, which is the property that matters.
 * - **Reads are streams that do not give up.** A stream the rules refuse, or that drops, is retried
 *   with backoff, because both resolve themselves: the members of a room become readable the moment
 *   this rider joins it, and a lost signal comes back.
 */
class RtdbRealtimeClient(
    override val selfId: String,
    override val selfName: String,
    private val database: RtdbDatabase,
    private val credentials: RtdbCredentials,
    private val http: HttpClient,
    private val random: Random = Random.Default,
) : RealtimeClient {

    private val _connected = MutableStateFlow(true)
    override val connected: Flow<Boolean> = _connected

    // ─────────────────────────────── rooms

    override suspend fun createRoom(name: String, code: JoinCode, now: Instant): RealtimeResult<Room> {
        // Checked first, so a taken code reads as taken rather than as whatever a refused write means.
        when (val existing = get(RtdbWire.codePath(code))) {
            is RealtimeResult.Failure -> return existing
            is RealtimeResult.Success -> if (existing.value != JsonNull) return realtimeFailure(RealtimeError.CODE_TAKEN)
        }
        val roomId = newRoomId()
        when (val claim = put(RtdbWire.codePath(code), JsonPrimitive(roomId))) {
            is RealtimeResult.Failure -> return realtimeFailure(
                // Write-once: refused here means somebody claimed it between the check and now.
                if (claim.error == RealtimeError.NOT_PERMITTED) RealtimeError.CODE_TAKEN else claim.error,
            )
            is RealtimeResult.Success -> Unit
        }
        val leader = Member(riderId = selfId, displayName = selfName, role = Role.LEADER)
        val room = Room(
            id = roomId,
            code = code,
            name = name,
            visibility = Visibility.INVITE_ONLY,
            maxRiders = Room.MAX_RIDERS,
            state = RoomState.LOBBY,
            leaderId = selfId,
            members = listOf(leader),
            createdAt = now,
        )
        put(RtdbWire.metaPath(roomId), RtdbWire.encodeMeta(room)).failureOrNull()?.let { return it }
        put(RtdbWire.memberPath(roomId, selfId), RtdbWire.encodeMember(leader)).failureOrNull()?.let { return it }
        return room.asRealtimeSuccess()
    }

    override suspend fun findRoom(code: JoinCode): RealtimeResult<Room?> {
        val roomId = when (val found = get(RtdbWire.codePath(code))) {
            is RealtimeResult.Failure -> return found
            is RealtimeResult.Success -> (found.value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return null.asRealtimeSuccess()
        }
        return when (val meta = get(RtdbWire.metaPath(roomId))) {
            is RealtimeResult.Failure -> meta
            // Members are not readable from outside the room, so the room comes back without them.
            is RealtimeResult.Success -> RtdbWire.decodeRoom(roomId, meta.value, members = null).asRealtimeSuccess()
        }
    }

    override suspend fun join(roomId: String, member: Member, now: Instant): RealtimeResult<Room> {
        if (member.riderId != selfId) return realtimeFailure(RealtimeError.NOT_PERMITTED)
        val before = when (val meta = readRoom(roomId, withMembers = false)) {
            is RealtimeResult.Failure -> return meta
            is RealtimeResult.Success -> meta.value
        }
        if (before.state == RoomState.ENDED || RoomStateMachine.isExpired(before.state, before.createdAt, before.endedAt, now)) {
            return realtimeFailure(RealtimeError.ROOM_GONE)
        }
        put(RtdbWire.memberPath(roomId, selfId), RtdbWire.encodeMember(member)).failureOrNull()?.let { return it }
        val after = when (val read = readRoom(roomId, withMembers = true)) {
            is RealtimeResult.Failure -> return read
            is RealtimeResult.Success -> read.value
        }
        if (after.members.size > after.maxRiders) {
            delete(RtdbWire.memberPath(roomId, selfId))
            return realtimeFailure(RealtimeError.NOT_PERMITTED)
        }
        return after.asRealtimeSuccess()
    }

    override suspend fun leave(roomId: String, now: Instant): RealtimeResult<Unit> {
        readRoom(roomId, withMembers = false).failureOrNull()?.let { return it }
        // Position first: writing it, removal included, needs the membership that goes next.
        delete(RtdbWire.positionPath(roomId, selfId)).failureOrNull()?.let { return it }
        return delete(RtdbWire.memberPath(roomId, selfId))
    }

    override suspend fun setState(roomId: String, state: RoomState, now: Instant): RealtimeResult<Unit> {
        val room = when (val read = readRoom(roomId, withMembers = false)) {
            is RealtimeResult.Failure -> return read
            is RealtimeResult.Success -> read.value
        }
        if (RoomStateMachine.isExpired(room.state, room.createdAt, room.endedAt, now)) {
            return realtimeFailure(RealtimeError.ROOM_GONE)
        }
        val change = buildJsonObject {
            put("state", state.name)
            if (state == RoomState.ENDED) put("endedAt", now.toEpochMilliseconds())
        }
        return patch(RtdbWire.metaPath(roomId), change)
    }

    override suspend fun setSweep(roomId: String, riderId: String?, now: Instant): RealtimeResult<Unit> {
        val room = when (val read = readRoom(roomId, withMembers = true)) {
            is RealtimeResult.Failure -> return read
            is RealtimeResult.Success -> read.value
        }
        if (riderId != null && room.member(riderId) == null) return realtimeFailure(RealtimeError.NOT_PERMITTED)
        // One multi-path update, so there is never a moment with two sweeps or none half-way through.
        val change = buildJsonObject {
            room.members.forEach { put("${it.riderId}/isSweep", it.riderId == riderId) }
        }
        return patch("${RtdbWire.roomPath(roomId)}/members", change)
    }

    override fun observeRoom(roomId: String): Flow<Room?> =
        combine(stream(RtdbWire.metaPath(roomId)), stream("${RtdbWire.roomPath(roomId)}/members")) { meta, members ->
            RtdbWire.decodeRoom(roomId, meta, members)
        }.distinctUntilChanged()

    // ─────────────────────────────── the ride

    override suspend fun publishPosition(roomId: String, sample: RiderSample): RealtimeResult<Unit> {
        if (sample.riderId != selfId) return realtimeFailure(RealtimeError.NOT_PERMITTED)
        return explainRefusal(roomId, put(RtdbWire.positionPath(roomId, selfId), RtdbWire.encodePosition(sample)))
    }

    override fun observePositions(roomId: String): Flow<Map<String, RiderSample>> =
        stream(RtdbWire.positionsPath(roomId)).map(RtdbWire::decodePositions).distinctUntilChanged()

    override suspend fun publishEvent(roomId: String, event: RideEvent): RealtimeResult<Unit> {
        if (event.riderId != selfId) return realtimeFailure(RealtimeError.NOT_PERMITTED)
        // POST: the database names the event, so two riders' events can never land on one key.
        return explainRefusal(roomId, call(HttpMethod.Post, RtdbWire.eventsPath(roomId), RtdbWire.encodeEvent(event)).unit())
    }

    override fun observeEvents(roomId: String): Flow<List<RideEvent>> =
        stream(RtdbWire.eventsPath(roomId)).map(RtdbWire::decodeEvents).distinctUntilChanged()

    // ─────────────────────────────── reads

    /** The room, or [RealtimeError.ROOM_GONE] when there is no meta to read. */
    private suspend fun readRoom(roomId: String, withMembers: Boolean): RealtimeResult<Room> {
        val meta = when (val read = get(RtdbWire.metaPath(roomId))) {
            is RealtimeResult.Failure -> return read
            is RealtimeResult.Success -> read.value
        }
        val members = if (withMembers) {
            when (val read = get("${RtdbWire.roomPath(roomId)}/members")) {
                is RealtimeResult.Failure -> return read
                is RealtimeResult.Success -> read.value
            }
        } else {
            null
        }
        return RtdbWire.decodeRoom(roomId, meta, members)?.asRealtimeSuccess() ?: realtimeFailure(RealtimeError.ROOM_GONE)
    }

    /**
     * A refused write to a room's ride data means one of two things, and a rider deserves to know
     * which: the room ended and took the membership with it, or this rider is not in it.
     */
    private suspend fun explainRefusal(roomId: String, result: RealtimeResult<Unit>): RealtimeResult<Unit> {
        if (result.errorOrNull != RealtimeError.NOT_PERMITTED) return result
        return when (val meta = get(RtdbWire.metaPath(roomId))) {
            is RealtimeResult.Success -> if (meta.value == JsonNull) realtimeFailure(RealtimeError.ROOM_GONE) else result
            is RealtimeResult.Failure -> result
        }
    }

    // ─────────────────────────────── the wire

    private suspend fun get(path: String) = call(HttpMethod.Get, path, body = null)
    private suspend fun put(path: String, body: JsonElement) = call(HttpMethod.Put, path, body).unit()
    private suspend fun patch(path: String, body: JsonElement) = call(HttpMethod.Patch, path, body).unit()
    private suspend fun delete(path: String) = call(HttpMethod.Delete, path, body = null).unit()

    private suspend fun call(method: HttpMethod, path: String, body: JsonElement?): RealtimeResult<JsonElement> {
        val token = credentials.idToken()
        val response = try {
            http.request(url(path)) {
                this.method = method
                database.namespace?.let { parameter("ns", it) }
                token?.let { parameter("auth", it) }
                body?.let { setBody(TextContent(it.toString(), ContentType.Application.Json)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No response at all: no network, or nothing listening. Usually momentary.
            _connected.value = false
            return realtimeFailure(RealtimeError.OFFLINE)
        }
        _connected.value = true
        if (!response.status.isSuccess()) return realtimeFailure(errorFor(response.status))
        return runCatching { Json.parseToJsonElement(response.bodyAsText()) }
            .getOrElse { return realtimeFailure(RealtimeError.UNKNOWN) }
            .asRealtimeSuccess()
    }

    private fun url(path: String): String = "${database.url.trimEnd('/')}/$path.json"

    /**
     * A location's data and every change to it, as a flow of the whole value: null when there is
     * nothing there, or when the rules will not show it.
     */
    private fun stream(path: String): Flow<JsonElement?> = channelFlow {
        var failures = 0
        while (true) {
            when (listenOnce(path)) {
                StreamEnd.Closed -> {
                    failures = 0
                    delay(backoff(0))
                }
                // Not backed off like a failure: a refusal ends the moment this rider joins, and
                // the lobby should show who is in it within seconds of that, not after half a minute.
                StreamEnd.Refused -> {
                    send(null)
                    delay(REFUSED_RETRY)
                }
                StreamEnd.Failed -> delay(backoff(++failures))
            }
        }
    }

    private suspend fun ProducerScope<JsonElement?>.listenOnce(path: String): StreamEnd {
        val token = credentials.idToken()
        return try {
            http.prepareGet(url(path)) {
                header(HttpHeaders.Accept, "text/event-stream")
                database.namespace?.let { parameter("ns", it) }
                token?.let { parameter("auth", it) }
            }.execute { response ->
                if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                    return@execute StreamEnd.Refused
                }
                if (!response.status.isSuccess()) return@execute StreamEnd.Failed
                _connected.value = true
                val tree = RtdbTree()
                val parser = ServerSentEventParser()
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readLine() ?: return@execute StreamEnd.Closed
                    when (val event = parser.feed(line)?.let(RtdbStreamEvent::from)) {
                        is RtdbStreamEvent.Put, is RtdbStreamEvent.Patch -> {
                            tree.apply(event)
                            send(tree.root)
                        }
                        RtdbStreamEvent.Cancel -> return@execute StreamEnd.Refused
                        RtdbStreamEvent.AuthRevoked -> return@execute StreamEnd.Closed
                        RtdbStreamEvent.KeepAlive, null -> Unit
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                StreamEnd.Closed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _connected.value = false
            StreamEnd.Failed
        }
    }

    private enum class StreamEnd {
        /** The server ended it, or the credential expired: listen again straight away. */
        Closed,

        /** The rules will not show it, at least for now. */
        Refused,

        /** The network or the server failed. */
        Failed,
    }

    /** 0.5 s, doubling, capped at 30 s. Every phone on a ride retrying at once should not be a stampede. */
    private fun backoff(failures: Int): Duration =
        if (failures == 0) 100.milliseconds else (500L shl (failures - 1).coerceAtMost(6)).milliseconds.coerceAtMost(30.seconds)

    /** A key the database accepts and nobody will guess: twenty letters and digits. */
    private fun newRoomId(): String = buildString { repeat(20) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) } }

    private fun RealtimeResult<JsonElement>.unit(): RealtimeResult<Unit> = when (this) {
        is RealtimeResult.Success -> Unit.asRealtimeSuccess()
        is RealtimeResult.Failure -> this
    }

    private fun RealtimeResult<*>.failureOrNull(): RealtimeResult.Failure? = this as? RealtimeResult.Failure

    private companion object {
        const val ID_ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val REFUSED_RETRY = 2.seconds

        fun errorFor(status: HttpStatusCode): RealtimeError = when (status.value) {
            401, 403 -> RealtimeError.NOT_PERMITTED
            502, 503, 504 -> RealtimeError.OFFLINE
            else -> RealtimeError.UNKNOWN
        }
    }
}
