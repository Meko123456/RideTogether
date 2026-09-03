package io.github.meko123456.ridetogether.realtime

import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.model.JoinCode
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.model.Room
import io.github.meko123456.ridetogether.model.RoomState
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Everything the app needs from a backend, and nothing about which backend it is.
 *
 * The interface exists before the implementation on purpose. The alert engine, the announcer and
 * the summariser are all pure and tested, and the fastest way to lose that is to let a networking
 * library's types — a Firebase `DataSnapshot`, a serialisation annotation, a callback shape — reach
 * into the modules that decide things. So the boundary is drawn here, in `commonMain`, in terms of
 * the domain's own types, and Firebase becomes one implementation of it (#10).
 *
 * Three properties the shape has to have, each learned from what the rest of the app already does:
 *
 * - **Reads are flows, writes are suspend functions.** Every consumer of room state is reactive
 *   already, and a poll-shaped API would push the caller back into deciding when to look.
 * - **Failures are values, not exceptions.** A dropped connection mid-ride is normal, not
 *   exceptional, and the UI has to say something specific about it — so [RealtimeError] is part of
 *   the return type rather than something thrown past the caller.
 * - **Positions are the domain's [RiderSample]**, so the alert engine can be fed straight from the
 *   wire without a translation layer that could quietly change units or lose the reporting
 *   interval that staleness detection depends on.
 */
interface RealtimeClient {

    /** Who this client is acting as. Every write is attributed to them. */
    val selfId: String

    // ─────────────────────────────── rooms

    /** Creates a room and returns it, or the reason it could not be created. */
    suspend fun createRoom(name: String, code: JoinCode, now: Instant): RealtimeResult<Room>

    /** Looks a room up by its code. Absent is not an error — it is a `null` room. */
    suspend fun findRoom(code: JoinCode): RealtimeResult<Room?>

    /** Joins a room as [member]. */
    suspend fun join(roomId: String, member: Member, now: Instant): RealtimeResult<Room>

    /** Leaves, removing presence. Called on ending a ride and on backing out of the room. */
    suspend fun leave(roomId: String, now: Instant): RealtimeResult<Unit>

    /** Changes the room's state. The caller has already asked the state machine whether it may. */
    suspend fun setState(roomId: String, state: RoomState, now: Instant): RealtimeResult<Unit>

    /** The room as it changes, including membership. Emits null once the room is gone. */
    fun observeRoom(roomId: String): Flow<Room?>

    // ─────────────────────────────── the ride

    /**
     * Publishes this rider's position. Deliberately fire-and-forget in its result handling: a
     * failed position is not worth telling the rider about, since another is due in seconds, and
     * an error dialog per dropped packet would be unusable on a bike.
     */
    suspend fun publishPosition(roomId: String, sample: RiderSample): RealtimeResult<Unit>

    /** Everyone's latest position, keyed by rider id — the alert engine's input. */
    fun observePositions(roomId: String): Flow<Map<String, RiderSample>>

    /** Appends to the ride log. */
    suspend fun publishEvent(roomId: String, event: RideEvent): RealtimeResult<Unit>

    /** The ride log as it grows, oldest first. */
    fun observeEvents(roomId: String): Flow<List<RideEvent>>

    /** Whether the client currently believes it is connected, for the UI to be honest about. */
    val connected: Flow<Boolean>
}

/** A result that carries its failure as a value, because a dropped connection is not exceptional. */
sealed interface RealtimeResult<out T> {
    data class Success<out T>(val value: T) : RealtimeResult<T>
    data class Failure(val error: RealtimeError) : RealtimeResult<Nothing>

    val valueOrNull: T? get() = (this as? Success)?.value
    val errorOrNull: RealtimeError? get() = (this as? Failure)?.error
}

/**
 * Why a call did not work. Named cases rather than a message string, because each one has a
 * different thing to say to a rider and a different thing for the app to do about it.
 */
enum class RealtimeError {
    /** No network, or the backend is unreachable. Retryable, and usually momentary. */
    OFFLINE,

    /** The room was there and now is not — expired, or ended and cleaned up. */
    ROOM_GONE,

    /** The backend refused: the rules said no. Not retryable without changing something. */
    NOT_PERMITTED,

    /** A code collision when creating a room. The caller should generate another and retry. */
    CODE_TAKEN,

    /** Anything the client could not classify. Kept last so the others stay meaningful. */
    UNKNOWN,
}

/** Convenience for the common case of wrapping a value. */
fun <T> T.asRealtimeSuccess(): RealtimeResult<T> = RealtimeResult.Success(this)

/** Convenience for the common case of reporting a failure. */
fun realtimeFailure(error: RealtimeError): RealtimeResult<Nothing> = RealtimeResult.Failure(error)
