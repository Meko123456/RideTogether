package io.github.meko123456.ridetogether.realtime

import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.model.JoinCode
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.model.Role
import io.github.meko123456.ridetogether.model.Room
import io.github.meko123456.ridetogether.model.RoomState
import io.github.meko123456.ridetogether.model.Visibility
import io.github.meko123456.ridetogether.room.JoinOutcome
import io.github.meko123456.ridetogether.room.JoinPolicy
import io.github.meko123456.ridetogether.room.JoinRefusal
import io.github.meko123456.ridetogether.room.RoomStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/**
 * A [RealtimeClient] backed by nothing but memory.
 *
 * Two jobs, and it is worth being clear that the second is the important one:
 *
 * 1. It lets the app run and be demonstrated before Firebase exists, which is what it is doing
 *    today — the room map that used to live inline in the Android view model now lives behind the
 *    same interface the real client will implement, so wiring Firebase in later changes one
 *    construction site rather than the view model's logic.
 * 2. **It is the test double.** Every behaviour that depends on a backend — a room expiring
 *    mid-ride, a join refused because the room is full, a code collision — can be driven from a
 *    unit test at whatever moment the test chooses, which is not something a Firebase emulator
 *    makes easy.
 *
 * It applies the same domain rules the real backend's security rules will have to: [JoinPolicy]
 * decides admissions and [RoomStateMachine] decides expiry. Keeping those decisions here rather
 * than assuming the server will make them is what stops the two implementations diverging.
 */
class InMemoryRealtimeClient(
    override val selfId: String,
    /** Flip to simulate losing the network. Every call then fails with [RealtimeError.OFFLINE]. */
    var online: Boolean = true,
) : RealtimeClient {

    private val rooms = mutableMapOf<String, MutableStateFlow<Room?>>()
    private val positions = mutableMapOf<String, MutableStateFlow<Map<String, RiderSample>>>()
    private val events = mutableMapOf<String, MutableStateFlow<List<RideEvent>>>()
    private val _connected = MutableStateFlow(true)

    override val connected: Flow<Boolean> = _connected

    /** Every position ever published, so a test can assert what actually went to the wire. */
    val published = mutableListOf<Pair<String, RiderSample>>()

    override suspend fun createRoom(name: String, code: JoinCode, now: Instant): RealtimeResult<Room> {
        offline()?.let { return it }
        if (rooms.keys.any { it == code.value }) return realtimeFailure(RealtimeError.CODE_TAKEN)
        val room = Room(
            id = code.value,
            code = code,
            name = name,
            visibility = Visibility.INVITE_ONLY,
            maxRiders = Room.MAX_RIDERS,
            state = RoomState.LOBBY,
            leaderId = selfId,
            // Role.LEADER, not the default RIDER: leaderId alone is not enough, because every
            // permission check goes through the *member's* role. Getting this wrong meant the
            // creator of a ride could not start it — the state machine refused with
            // NOT_PERMITTED, which is exactly right given what it was told.
            members = listOf(Member(riderId = selfId, displayName = "You", role = Role.LEADER)),
            createdAt = now,
        )
        flowFor(room.id).value = room
        return room.asRealtimeSuccess()
    }

    override suspend fun findRoom(code: JoinCode): RealtimeResult<Room?> {
        offline()?.let { return it }
        return rooms[code.value]?.value.asRealtimeSuccess()
    }

    override suspend fun join(roomId: String, member: Member, now: Instant): RealtimeResult<Room> {
        offline()?.let { return it }
        val flow = rooms[roomId] ?: return realtimeFailure(RealtimeError.ROOM_GONE)
        val room = flow.value ?: return realtimeFailure(RealtimeError.ROOM_GONE)
        // The same policy the backend's rules will enforce, applied here so the two cannot drift.
        return when (val outcome = JoinPolicy.evaluate(room, member.riderId, now)) {
            JoinOutcome.Admitted, JoinOutcome.AwaitingApproval -> {
                val updated = room.copy(members = room.members + member)
                flow.value = updated
                updated.asRealtimeSuccess()
            }
            JoinOutcome.AlreadyMember -> room.asRealtimeSuccess()
            is JoinOutcome.Refused -> realtimeFailure(
                when (outcome.reason) {
                    JoinRefusal.ROOM_FULL -> RealtimeError.NOT_PERMITTED
                    JoinRefusal.ROOM_ENDED, JoinRefusal.ROOM_EXPIRED -> RealtimeError.ROOM_GONE
                },
            )
        }
    }

    override suspend fun leave(roomId: String, now: Instant): RealtimeResult<Unit> {
        offline()?.let { return it }
        val flow = rooms[roomId] ?: return realtimeFailure(RealtimeError.ROOM_GONE)
        val room = flow.value ?: return realtimeFailure(RealtimeError.ROOM_GONE)
        flow.value = room.copy(members = room.members.filterNot { it.riderId == selfId })
        // Presence going away takes the position with it: a rider who left should not linger on
        // the map at their last known spot.
        positions[roomId]?.let { it.value = it.value - selfId }
        return Unit.asRealtimeSuccess()
    }

    override suspend fun setState(roomId: String, state: RoomState, now: Instant): RealtimeResult<Unit> {
        offline()?.let { return it }
        val flow = rooms[roomId] ?: return realtimeFailure(RealtimeError.ROOM_GONE)
        val room = flow.value ?: return realtimeFailure(RealtimeError.ROOM_GONE)
        if (RoomStateMachine.isExpired(room.state, room.createdAt, room.endedAt, now)) {
            return realtimeFailure(RealtimeError.ROOM_GONE)
        }
        flow.value = room.copy(
            state = state,
            endedAt = if (state == RoomState.ENDED) now else room.endedAt,
        )
        return Unit.asRealtimeSuccess()
    }

    override suspend fun setSweep(roomId: String, riderId: String?, now: Instant): RealtimeResult<Unit> {
        offline()?.let { return it }
        val flow = rooms[roomId] ?: return realtimeFailure(RealtimeError.ROOM_GONE)
        val room = flow.value ?: return realtimeFailure(RealtimeError.ROOM_GONE)
        if (riderId != null && room.member(riderId) == null) {
            return realtimeFailure(RealtimeError.NOT_PERMITTED)
        }
        // Exactly one sweep, enforced here rather than trusted from the caller: two back markers
        // means two riders the engine never prompts, which is the one thing this flag must not do.
        flow.value = room.copy(members = room.members.map { it.copy(isSweep = it.riderId == riderId) })
        return Unit.asRealtimeSuccess()
    }

    override fun observeRoom(roomId: String): Flow<Room?> = flowFor(roomId)

    override suspend fun publishPosition(roomId: String, sample: RiderSample): RealtimeResult<Unit> {
        offline()?.let { return it }
        if (rooms[roomId]?.value == null) return realtimeFailure(RealtimeError.ROOM_GONE)
        published += roomId to sample
        val flow = positions.getOrPut(roomId) { MutableStateFlow(emptyMap()) }
        flow.value = flow.value + (sample.riderId to sample)
        return Unit.asRealtimeSuccess()
    }

    override fun observePositions(roomId: String): Flow<Map<String, RiderSample>> =
        positions.getOrPut(roomId) { MutableStateFlow(emptyMap()) }

    override suspend fun publishEvent(roomId: String, event: RideEvent): RealtimeResult<Unit> {
        offline()?.let { return it }
        if (rooms[roomId]?.value == null) return realtimeFailure(RealtimeError.ROOM_GONE)
        val flow = events.getOrPut(roomId) { MutableStateFlow(emptyList()) }
        flow.value = flow.value + event
        return Unit.asRealtimeSuccess()
    }

    override fun observeEvents(roomId: String): Flow<List<RideEvent>> =
        events.getOrPut(roomId) { MutableStateFlow(emptyList()) }

    // ─────────────────────────────── test controls

    /** Simulates another rider's position arriving, which is most of what a test needs. */
    fun receivePosition(roomId: String, sample: RiderSample) {
        val flow = positions.getOrPut(roomId) { MutableStateFlow(emptyMap()) }
        flow.value = flow.value + (sample.riderId to sample)
    }

    /** Simulates someone else joining. */
    fun receiveMember(roomId: String, member: Member) {
        val flow = flowFor(roomId)
        flow.value = flow.value?.let { it.copy(members = it.members + member) }
    }

    /** Simulates the room disappearing under the app — expired, or cleaned up after ending. */
    fun dropRoom(roomId: String) {
        flowFor(roomId).value = null
    }

    fun setConnected(value: Boolean) {
        online = value
        _connected.value = value
    }

    /** A read-only view of a room, for assertions. */
    fun room(roomId: String): StateFlow<Room?> = flowFor(roomId)

    /** Rider ids currently visible on the map. */
    fun visibleRiders(roomId: String): Flow<Set<String>> =
        observePositions(roomId).map { it.keys }

    private fun flowFor(roomId: String): MutableStateFlow<Room?> =
        rooms.getOrPut(roomId) { MutableStateFlow(null) }

    private fun offline(): RealtimeResult<Nothing>? =
        if (online) null else realtimeFailure(RealtimeError.OFFLINE)
}
