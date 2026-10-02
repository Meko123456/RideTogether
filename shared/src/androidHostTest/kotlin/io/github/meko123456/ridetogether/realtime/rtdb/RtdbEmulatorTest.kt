package io.github.meko123456.ridetogether.realtime.rtdb

import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.model.JoinCode
import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.model.Role
import io.github.meko123456.ridetogether.model.Room
import io.github.meko123456.ridetogether.model.RoomState
import io.github.meko123456.ridetogether.realtime.RealtimeError
import io.github.meko123456.ridetogether.realtime.RealtimeResult
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import org.junit.Assume.assumeTrue

/**
 * RtdbRealtimeClient against the Firebase database emulator, with the repo's own
 * database.rules.json loaded, so what is tested is the client and the rules together.
 *
 * Runs only under `firebase emulators:exec --only database`, which sets
 * FIREBASE_DATABASE_EMULATOR_HOST; anywhere else every test is skipped. CI runs it in the rules job:
 *
 *     cd tools/rules-tests && ./node_modules/.bin/firebase emulators:exec --only database \
 *       "cd ../.. && ./gradlew :shared:testAndroidHostTest --tests '*RtdbEmulatorTest*'"
 */
class RtdbEmulatorTest {

    private val host: String? = System.getenv("FIREBASE_DATABASE_EMULATOR_HOST")

    /** A fresh database instance per test, so no test can see another's rooms. */
    private val namespace = "ridetogether-test-${UUID.randomUUID().toString().take(8)}"
    private val http = HttpClient()
    private val now get() = Clock.System.now()

    private lateinit var leader: RtdbRealtimeClient
    private lateinit var rider: RtdbRealtimeClient
    private lateinit var stranger: RtdbRealtimeClient

    @BeforeTest
    fun loadRules() = runBlocking<Unit> {
        assumeTrue("needs the database emulator: run under firebase emulators:exec", host != null)
        val rules = File("../database.rules.json").readText()
        val response = http.put("http://$host/.settings/rules.json?ns=$namespace") {
            header("Authorization", "Bearer owner")
            setBody(rules)
        }
        check(response.status.isSuccess()) { "the emulator refused the rules: ${response.bodyAsText()}" }
        leader = clientFor("leaderUid", "Merab")
        rider = clientFor("riderUid", "Ana")
        stranger = clientFor("strangerUid", "Stranger")
    }

    @AfterTest
    fun close() = http.close()

    private fun clientFor(uid: String, name: String) = RtdbRealtimeClient(
        selfId = uid,
        selfName = name,
        database = RtdbDatabase("http://$host", namespace),
        credentials = { unsignedToken(uid) },
        http = http,
    )

    /** What the emulator accepts in place of a real ID token, the same shape the rules tests use. */
    private fun unsignedToken(uid: String): String {
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val iat = System.currentTimeMillis() / 1000
        val header = b64.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = b64.encodeToString(
            """{"iss":"https://securetoken.google.com/demo-ridetogether","aud":"demo-ridetogether","iat":$iat,"exp":${iat + 3600},"auth_time":$iat,"sub":"$uid","user_id":"$uid","firebase":{"identities":{},"sign_in_provider":"custom"}}"""
                .toByteArray(),
        )
        return "$header.$payload."
    }

    private fun code() = JoinCode.generate { Random.nextInt(it) }

    private fun <T> RealtimeResult<T>.value(): T = when (this) {
        is RealtimeResult.Success -> value
        is RealtimeResult.Failure -> throw AssertionError("expected success, got $error")
    }

    private suspend fun roomWithRider(): Room {
        val room = leader.createRoom("Sunday run", code(), now).value()
        rider.join(room.id, Member("riderUid", "Ana"), now).value()
        return room
    }

    @Test
    fun `a rider finds a room by its code but cannot see who is in it before joining`() = runBlocking<Unit> {
        val code = code()
        val created = leader.createRoom("Sunday run", code, now).value()
        val found = rider.findRoom(code).value()
        assertEquals(created.id, found?.id)
        assertEquals("Sunday run", found?.name)
        assertEquals(emptyList(), found?.members, "members are members-only")
        assertEquals(RealtimeResult.Success(null), rider.findRoom(code()), "an unused code is no room")
    }

    @Test
    fun `a code already claimed cannot be claimed again`() = runBlocking<Unit> {
        val code = code()
        leader.createRoom("Sunday run", code, now).value()
        assertEquals(RealtimeError.CODE_TAKEN, rider.createRoom("Hijack", code, now).errorOrNull)
    }

    @Test
    fun `once a rider joins both of them see both of them`() = runBlocking<Unit> {
        val room = roomWithRider()
        withTimeout(15.seconds) {
            for (viewer in listOf(leader, rider)) {
                val seen = viewer.observeRoom(room.id).first { it?.members?.size == 2 }!!
                assertEquals(listOf(Role.LEADER, Role.RIDER), seen.members.map { it.role })
            }
        }
    }

    @Test
    fun `only a leader can change the state of the ride`() = runBlocking<Unit> {
        val room = roomWithRider()
        assertEquals(RealtimeError.NOT_PERMITTED, rider.setState(room.id, RoomState.RIDING, now).errorOrNull)
        leader.setState(room.id, RoomState.RIDING, now).value()
        withTimeout(15.seconds) { rider.observeRoom(room.id).first { it?.state == RoomState.RIDING } }
    }

    @Test
    fun `members see each other's positions with the reporting interval and strangers see nothing`() = runBlocking<Unit> {
        val room = roomWithRider()
        val sample = RiderSample("riderUid", LatLng(41.7151, 44.8271), 12.5f, now, reportingInterval = 5.seconds)
        rider.publishPosition(room.id, sample).value()
        withTimeout(15.seconds) {
            val seen = leader.observePositions(room.id).first { "riderUid" in it }
            assertEquals(5.seconds, seen.getValue("riderUid").reportingInterval)
            assertEquals(emptyMap(), stranger.observePositions(room.id).first())
        }
        assertEquals(
            RealtimeError.NOT_PERMITTED,
            stranger.publishPosition(room.id, sample.copy(riderId = "strangerUid")).errorOrNull,
        )
    }

    @Test
    fun `the ride log reads back in order`() = runBlocking<Unit> {
        val room = roomWithRider()
        val t = now
        rider.publishEvent(room.id, RideEvent.Joined(t, "riderUid")).value()
        leader.publishEvent(room.id, RideEvent.StateChanged(t + 1.seconds, "leaderUid", RoomState.LOBBY, RoomState.RIDING)).value()
        withTimeout(15.seconds) {
            val log = rider.observeEvents(room.id).first { it.size == 2 }
            assertEquals(listOf("Joined", "StateChanged"), log.map { RtdbWire.typeOf(it) })
        }
    }

    @Test
    fun `a full room turns the next rider away and does not keep them`() = runBlocking<Unit> {
        val room = roomWithRider()
        // Two places, both taken. Set past the rules, the way a smaller room would have been made.
        http.patch("http://$host/${RtdbWire.metaPath(room.id)}.json?ns=$namespace") {
            header("Authorization", "Bearer owner")
            setBody("""{"maxRiders":2}""")
        }
        assertEquals(RealtimeError.NOT_PERMITTED, stranger.join(room.id, Member("strangerUid", "Stranger"), now).errorOrNull)
        withTimeout(15.seconds) {
            val seen = leader.observeRoom(room.id).first { it != null && it.members.size == 2 }!!
            assertTrue(seen.members.none { it.riderId == "strangerUid" })
        }
    }

    @Test
    fun `leaving takes the rider and their position off everyone's map`() = runBlocking<Unit> {
        val room = roomWithRider()
        rider.publishPosition(room.id, RiderSample("riderUid", LatLng(41.7, 44.8), 9f, now)).value()
        rider.leave(room.id, now).value()
        withTimeout(15.seconds) {
            leader.observeRoom(room.id).first { it?.members?.size == 1 }
            leader.observePositions(room.id).first { "riderUid" !in it }
        }
    }

    @Test
    fun `the leader names exactly one sweep`() = runBlocking<Unit> {
        val room = roomWithRider()
        leader.setSweep(room.id, "riderUid", now).value()
        withTimeout(15.seconds) {
            val seen = rider.observeRoom(room.id).first { r -> r?.members?.any { it.isSweep } == true }!!
            assertEquals(listOf("riderUid"), seen.members.filter { it.isSweep }.map { it.riderId })
        }
        assertEquals(RealtimeError.NOT_PERMITTED, rider.setSweep(room.id, "leaderUid", now).errorOrNull)
    }

    @Test
    fun `an ended and expired room is gone to someone trying to join`() = runBlocking<Unit> {
        val room = leader.createRoom("Sunday run", code(), now).value()
        leader.setState(room.id, RoomState.ENDED, now).value()
        assertEquals(RealtimeError.ROOM_GONE, rider.join(room.id, Member("riderUid", "Ana"), now).errorOrNull)
        assertNull(stranger.findRoom(JoinCode("ZZZZZZ")).value())
    }
}
