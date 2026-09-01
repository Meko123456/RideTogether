package io.github.meko123456.ridetogether.alerts

import io.github.meko123456.ridetogether.model.FallbackResponse
import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.RiderStatus
import io.github.meko123456.ridetogether.model.Role
import io.github.meko123456.ridetogether.model.RoomState
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Synthetic GPS traces. The engine is pure, so a "ride" here is just a list of ticks — which is
 * the whole point of keeping platform code out of it.
 *
 * Geometry: everything runs due east along the equator, where one degree of longitude is
 * ~111.32 km, so 0.001° ≈ 111 m. That makes the intended gaps readable in the test itself.
 */
class AlertEngineTest {

    private val t0 = Instant.parse("2026-09-01T09:00:00Z")
    private val leader = Member("leader", "Merab", Role.LEADER)
    private val rider = Member("alex", "Alex", Role.RIDER)
    private val sweep = Member("sweep", "Nika", Role.RIDER, isSweep = true)

    /** Metres east of the origin, as a longitude. */
    private fun east(meters: Double) = LatLng(0.0, meters / 111_320.0)

    private fun sample(id: String, meters: Double, speed: Float, at: Instant) =
        RiderSample(id, east(meters), speed, at)

    private fun tick(
        at: Instant,
        leaderMeters: Double,
        riderMeters: Double,
        riderSpeed: Float = 20f,
        members: List<Member> = listOf(leader, rider),
        state: RoomState = RoomState.RIDING,
        includeRider: Boolean = true,
    ): EngineTick {
        val samples = buildMap {
            put("leader", sample("leader", leaderMeters, 20f, at))
            if (includeRider) put(rider.riderId, sample(rider.riderId, riderMeters, riderSpeed, at))
        }
        return EngineTick(now = at, roomState = state, members = members, samples = samples)
    }

    /** Runs the engine over a series of ticks and returns every alert emitted, in order. */
    private fun run(engine: AlertEngine, ticks: List<EngineTick>): List<Alert> =
        ticks.flatMap { engine.assess(it).alerts }

    /** Warms a rider past the join grace so the gap logic applies to them. */
    private fun warmUp(engine: AlertEngine, from: Instant = t0, seconds: Int = 200): Instant {
        var at = from
        var travelled = 0.0
        repeat(seconds / 5) {
            engine.assess(tick(at, leaderMeters = travelled + 100.0, riderMeters = travelled))
            travelled += 80.0
            at += 5.seconds
        }
        return at
    }

    // ---------------------------------------------------------------- red light

    @Test
    fun `a long red light never raises the loud group alert, and the prompt withdraws itself`() {
        // The honest case. A 100-second light at 60 km/h opens 1.77 km, so the gap genuinely
        // crosses the 1.5 km threshold while rising the whole time — a position trace simply
        // cannot tell this from a breakdown. What must hold is:
        //   * the group is never alarmed (no PossibleIncident), and
        //   * once the rider pulls away and starts closing, the pending question is withdrawn.
        val engine = AlertEngine()
        var at = warmUp(engine)
        val alerts = mutableListOf<Alert>()

        var leaderPos = 2_000.0
        val riderPos = 1_900.0
        // 100 s stopped at the line; the leader rides on at ~16.7 m/s (60 km/h).
        repeat(20) {
            alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 0f)).alerts
            leaderPos += 16.7 * 5
            at += 5.seconds
        }
        assertTrue(
            (leaderPos - riderPos) > 1_500.0,
            "the trace must actually cross the threshold or this test proves nothing",
        )
        assertTrue(
            alerts.filterIsInstance<Alert.PossibleIncident>().isEmpty(),
            "a traffic light must never raise the loud alert: $alerts",
        )

        // Green: the rider accelerates hard and starts closing.
        var closing = riderPos
        repeat(12) {
            closing += 33.0 * 5
            alerts += engine.assess(tick(at, leaderPos, closing, riderSpeed = 33f)).alerts
            leaderPos += 16.7 * 5
            at += 5.seconds
        }
        assertTrue(
            alerts.filterIsInstance<Alert.PossibleIncident>().isEmpty(),
            "still no loud alert after they rejoin: $alerts",
        )
        val stillWaiting = engine.assess(tick(at, leaderPos, closing, riderSpeed = 33f))
            .assessments.first { it.riderId == rider.riderId }
        assertFalse(
            stillWaiting.awaitingResponse,
            "moving and closing withdraws the question, so nothing is left to escalate",
        )
    }

    @Test
    fun `a short light does not even raise the local prompt`() {
        // 60 s at 60 km/h only opens ~1 km, which is inside the threshold.
        val engine = AlertEngine()
        var at = warmUp(engine)
        val alerts = mutableListOf<Alert>()
        var leaderPos = 2_000.0
        val riderPos = 1_900.0
        repeat(12) {
            alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 0f)).alerts
            leaderPos += 16.7 * 5
            at += 5.seconds
        }
        assertTrue(alerts.filterIsInstance<Alert.FallingBehind>().isEmpty(), "got: $alerts")
    }

    // ------------------------------------------------------------ genuine fallback

    @Test
    fun `a rider who keeps losing ground is flagged`() {
        val engine = AlertEngine()
        var at = warmUp(engine)
        val alerts = mutableListOf<Alert>()
        var leaderPos = 2_000.0
        var riderPos = 1_900.0
        // The rider is moving, but slower — the gap grows steadily past the threshold.
        repeat(40) {
            alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 8f)).alerts
            leaderPos += 100.0
            riderPos += 40.0
            at += 5.seconds
        }
        val flagged = alerts.filterIsInstance<Alert.FallingBehind>()
        assertEquals(1, flagged.size, "expected exactly one alert, got $flagged")
        assertTrue(flagged.first().gapMeters > 1_500.0)
    }

    @Test
    fun `the alert is raised once, not on every tick`() {
        val engine = AlertEngine()
        var at = warmUp(engine)
        val alerts = mutableListOf<Alert>()
        var leaderPos = 2_000.0
        var riderPos = 1_900.0
        repeat(60) {
            alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 8f)).alerts
            leaderPos += 120.0
            riderPos += 40.0
            at += 5.seconds
        }
        assertEquals(1, alerts.filterIsInstance<Alert.FallingBehind>().size)
    }

    // ---------------------------------------------------------------- rejoining

    @Test
    fun `closing the gap clears the alert, but only past the hysteresis band`() {
        val engine = AlertEngine()
        var at = warmUp(engine)
        var leaderPos = 2_000.0
        var riderPos = 1_900.0
        val alerts = mutableListOf<Alert>()
        // Fall behind.
        repeat(40) {
            alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 8f)).alerts
            leaderPos += 120.0
            riderPos += 40.0
            at += 5.seconds
        }
        assertEquals(1, alerts.filterIsInstance<Alert.FallingBehind>().size)

        // Come back to just inside the threshold (1.4 km) — NOT enough to clear, by design.
        alerts.clear()
        riderPos = leaderPos - 1_400.0
        alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 30f)).alerts
        at += 5.seconds
        assertTrue(
            alerts.filterIsInstance<Alert.Rejoined>().isEmpty(),
            "1.4 km is inside the hysteresis band and must not clear the alert",
        )

        // Properly rejoined, well under 75 % of the threshold.
        riderPos = leaderPos - 500.0
        alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 30f)).alerts
        assertEquals(1, alerts.filterIsInstance<Alert.Rejoined>().size)
    }

    // ---------------------------------------------------------------- escalation

    @Test
    fun `no answer plus stationary escalates to a possible incident`() {
        val engine = AlertEngine()
        var at = warmUp(engine)
        var leaderPos = 2_000.0
        var riderPos = 1_900.0
        val alerts = mutableListOf<Alert>()
        repeat(40) {
            alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 8f)).alerts
            leaderPos += 120.0
            riderPos += 40.0
            at += 5.seconds
        }
        assertEquals(1, alerts.filterIsInstance<Alert.FallingBehind>().size)

        // Rider stops dead and says nothing for over 90 s, but keeps reporting position.
        alerts.clear()
        repeat(30) {
            alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 0f)).alerts
            leaderPos += 120.0
            at += 5.seconds
        }
        assertEquals(1, alerts.filterIsInstance<Alert.PossibleIncident>().size, "should escalate exactly once")
    }

    @Test
    fun `answering the prompt stops the escalation`() {
        val engine = AlertEngine()
        var at = warmUp(engine)
        var leaderPos = 2_000.0
        var riderPos = 1_900.0
        repeat(40) {
            engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 8f))
            leaderPos += 120.0
            riderPos += 40.0
            at += 5.seconds
        }
        engine.onResponse(rider.riderId, FallbackResponse.FINE)

        val alerts = mutableListOf<Alert>()
        repeat(40) {
            alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 0f)).alerts
            leaderPos += 120.0
            at += 5.seconds
        }
        assertTrue(
            alerts.filterIsInstance<Alert.PossibleIncident>().isEmpty(),
            "a rider who said they're fine must not be escalated",
        )
    }

    @Test
    fun `a rider still moving is not escalated even without an answer`() {
        val engine = AlertEngine()
        var at = warmUp(engine)
        var leaderPos = 2_000.0
        var riderPos = 1_900.0
        val alerts = mutableListOf<Alert>()
        // Falls behind and never answers, but keeps riding — they are dealing with it.
        repeat(80) {
            alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 10f)).alerts
            leaderPos += 120.0
            riderPos += 50.0
            at += 5.seconds
        }
        assertTrue(alerts.filterIsInstance<Alert.PossibleIncident>().isEmpty())
    }

    // ------------------------------------------------------------- signal loss

    @Test
    fun `going quiet is reported as signal lost, never as an incident`() {
        val engine = AlertEngine()
        var at = warmUp(engine)
        val alerts = mutableListOf<Alert>()
        // The rider's phone stops reporting entirely; the leader keeps going.
        var leaderPos = 2_000.0
        repeat(20) {
            alerts += engine.assess(
                tick(at, leaderPos, riderMeters = 0.0, includeRider = false),
            ).alerts
            leaderPos += 100.0
            at += 5.seconds
        }
        assertEquals(1, alerts.filterIsInstance<Alert.SignalLost>().size)
        assertTrue(
            alerts.filterIsInstance<Alert.PossibleIncident>().isEmpty(),
            "no data is not evidence of a crash (spec 2.3.5)",
        )
    }

    @Test
    fun `signal coming back is announced once`() {
        val engine = AlertEngine()
        var at = warmUp(engine)
        val alerts = mutableListOf<Alert>()
        repeat(20) {
            alerts += engine.assess(tick(at, 2_000.0, 0.0, includeRider = false)).alerts
            at += 5.seconds
        }
        assertEquals(1, alerts.filterIsInstance<Alert.SignalLost>().size)
        alerts.clear()
        repeat(3) {
            alerts += engine.assess(tick(at, 2_000.0, 1_950.0)).alerts
            at += 5.seconds
        }
        assertEquals(1, alerts.filterIsInstance<Alert.SignalRestored>().size)
    }

    @Test
    fun `an unanswered prompt while data is stale does not escalate`() {
        // The honest outcome is "we don't know", not "possible crash".
        val engine = AlertEngine()
        var at = warmUp(engine)
        var leaderPos = 2_000.0
        var riderPos = 1_900.0
        repeat(40) {
            engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 8f))
            leaderPos += 120.0
            riderPos += 40.0
            at += 5.seconds
        }
        val alerts = mutableListOf<Alert>()
        repeat(40) {
            alerts += engine.assess(tick(at, leaderPos, 0.0, includeRider = false)).alerts
            leaderPos += 120.0
            at += 5.seconds
        }
        assertTrue(alerts.filterIsInstance<Alert.PossibleIncident>().isEmpty())
        assertEquals(1, alerts.filterIsInstance<Alert.SignalLost>().size)
    }

    // ------------------------------------------------------------------- paused

    @Test
    fun `a fuel stop raises nothing even while the gap is actively growing`() {
        // The gap must really be growing, or this test would pass with the suppression removed.
        val engine = AlertEngine()
        var at = warmUp(engine)
        val alerts = mutableListOf<Alert>()
        var leaderPos = 2_000.0
        val riderPos = 1_900.0
        repeat(40) {
            alerts += engine.assess(
                tick(at, leaderPos, riderPos, riderSpeed = 0f, state = RoomState.PAUSED),
            ).alerts
            leaderPos += 150.0
            at += 5.seconds
        }
        assertTrue((leaderPos - riderPos) > 1_500.0, "the gap must cross the threshold")
        assertTrue(
            alerts.filterIsInstance<Alert.FallingBehind>().isEmpty(),
            "separation alerts are suppressed while paused (spec 2.3.6): $alerts",
        )
        assertTrue(alerts.filterIsInstance<Alert.PossibleIncident>().isEmpty())
    }

    // -------------------------------------------------------------------- sweep

    @Test
    fun `the sweep is not flagged for being last, because that is their job`() {
        val engine = AlertEngine()
        val members = listOf(leader, sweep)
        var at = t0
        // Warm the sweep past the join grace.
        var travelled = 0.0
        repeat(18) {
            engine.assess(
                EngineTick(
                    now = at,
                    roomState = RoomState.RIDING,
                    members = members,
                    samples = mapOf(
                        "leader" to sample("leader", travelled + 100.0, 20f, at),
                        "sweep" to sample("sweep", travelled, 20f, at),
                    ),
                ),
            )
            travelled += 80.0
            at += 5.seconds
        }
        val alerts = mutableListOf<Alert>()
        var leaderPos = 2_000.0
        var sweepPos = 1_900.0
        repeat(40) {
            alerts += engine.assess(
                EngineTick(
                    now = at,
                    roomState = RoomState.RIDING,
                    members = members,
                    samples = mapOf(
                        "leader" to sample("leader", leaderPos, 20f, at),
                        "sweep" to sample("sweep", sweepPos, 10f, at),
                    ),
                ),
            ).alerts
            leaderPos += 120.0
            sweepPos += 40.0
            at += 5.seconds
        }
        assertTrue(
            alerts.filterIsInstance<Alert.FallingBehind>().isEmpty(),
            "the sweep is meant to be at the back: $alerts",
        )
    }

    // ------------------------------------------------------------- join grace

    @Test
    fun `a rider who just joined is not flagged even once the gap has been rising long enough`() {
        // Someone catching the group up starts kilometres behind by definition. This runs well
        // past the 60 s rising grace so it fails if the join grace is removed.
        val engine = AlertEngine()
        var at = t0
        val alerts = mutableListOf<Alert>()
        var leaderPos = 6_000.0
        var riderPos = 1_000.0
        repeat(20) { // 100 s — comfortably past gracePeriod, inside joinGrace
            alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 25f)).alerts
            leaderPos += 150.0
            riderPos += 60.0
            at += 5.seconds
        }
        assertTrue(
            alerts.filterIsInstance<Alert.FallingBehind>().isEmpty(),
            "a rider still inside the join grace must not be flagged: $alerts",
        )
    }

    // --------------------------------------------------------------- stationary

    @Test
    fun `one zero-speed sample is not enough to call a rider stopped`() {
        val engine = AlertEngine()
        val at = warmUp(engine)
        val result = engine.assess(tick(at, 2_000.0, 1_950.0, riderSpeed = 0f))
        val alex = result.assessments.first { it.riderId == rider.riderId }
        assertEquals(RiderStatus.ACTIVE, alex.status, "a single 0 m/s fix proves nothing")
    }

    @Test
    fun `a rider stationary for the whole window is reported stopped`() {
        val engine = AlertEngine()
        var at = warmUp(engine)
        lateinit var result: EngineResult
        repeat(10) {
            result = engine.assess(tick(at, 2_000.0, 1_950.0, riderSpeed = 0f))
            at += 5.seconds
        }
        assertEquals(RiderStatus.STOPPED, result.assessments.first { it.riderId == rider.riderId }.status)
    }

    // ----------------------------------------------------------------- stickiness

    @Test
    fun `an incident is not quietly downgraded when the phone goes quiet`() {
        // A crashed phone stops reporting. Turning the loud alert grey would hide the emergency.
        val engine = AlertEngine()
        var at = warmUp(engine)
        var leaderPos = 2_000.0
        var riderPos = 1_900.0
        repeat(40) {
            engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 8f))
            leaderPos += 120.0
            riderPos += 40.0
            at += 5.seconds
        }
        repeat(30) {
            engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 0f))
            leaderPos += 120.0
            at += 5.seconds
        }
        // Now the phone dies.
        lateinit var result: EngineResult
        repeat(30) {
            result = engine.assess(tick(at, leaderPos, 0.0, includeRider = false))
            leaderPos += 120.0
            at += 5.seconds
        }
        assertEquals(
            RiderStatus.POSSIBLE_INCIDENT,
            result.assessments.first { it.riderId == rider.riderId }.status,
            "an incident must stay loud even after the phone stops reporting",
        )
    }

    // --------------------------------------------------------------- freshness

    @Test
    fun `staleness scales with a slow reporter so stopped riders do not grey out`() {
        // A stopped rider on the 30 s adaptive interval must not be called stale at 45 s.
        val engine = AlertEngine()
        val at = warmUp(engine)
        val slow = RiderSample(rider.riderId, east(1_950.0), 0f, at - 50.seconds, reportingInterval = 30.seconds)
        val result = engine.assess(
            EngineTick(
                now = at,
                roomState = RoomState.RIDING,
                members = listOf(leader, rider),
                samples = mapOf(
                    "leader" to sample("leader", 2_000.0, 20f, at),
                    rider.riderId to slow,
                ),
            ),
        )
        assertFalse(
            result.assessments.first { it.riderId == rider.riderId }.isStale,
            "50 s is fine for a rider reporting every 30 s",
        )
    }

    @Test
    fun `a fast reporter going quiet for 50 seconds is stale`() {
        val engine = AlertEngine()
        val at = warmUp(engine)
        val quiet = RiderSample(rider.riderId, east(1_950.0), 20f, at - 50.seconds, reportingInterval = 4.seconds)
        val result = engine.assess(
            EngineTick(
                now = at,
                roomState = RoomState.RIDING,
                members = listOf(leader, rider),
                samples = mapOf(
                    "leader" to sample("leader", 2_000.0, 20f, at),
                    rider.riderId to quiet,
                ),
            ),
        )
        assertTrue(result.assessments.first { it.riderId == rider.riderId }.isStale)
    }

    // ------------------------------------------------- prompt withdrawal, precisely

    @Test
    fun `closing the gap withdraws the question even before the alert itself clears`() {
        // Between the threshold (1.5 km) and the clear band (1.125 km) the alert legitimately
        // stands, but the rider is visibly dealing with it — so the pending question goes away
        // and nothing can escalate. Without this the hysteresis band is a dead zone where a
        // rider who is actively catching up still gets reported as a possible incident.
        val engine = AlertEngine()
        var at = warmUp(engine)
        var leaderPos = 2_000.0
        var riderPos = 1_900.0
        repeat(40) {
            engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 8f))
            leaderPos += 120.0
            riderPos += 40.0
            at += 5.seconds
        }
        // Now closing, but still 1.3 km back — above the 1.125 km clear threshold.
        var result = engine.assess(tick(at, leaderPos, leaderPos - 1_400.0, riderSpeed = 33f))
        at += 5.seconds
        result = engine.assess(tick(at, leaderPos, leaderPos - 1_300.0, riderSpeed = 33f))
        val alex = result.assessments.first { it.riderId == rider.riderId }
        assertEquals(RiderStatus.FALLING_BEHIND, alex.status, "the alert legitimately still stands")
        assertFalse(alex.awaitingResponse, "but the question has been withdrawn — they are closing")
    }

    // ------------------------------------------- stopping late, briefly

    @Test
    fun `a rider who stops briefly after falling behind is not escalated`() {
        // Pulling over to check a strap is not an incident. Escalation needs a stop longer than
        // any plausible pause, which is what minStationaryForIncident is for.
        val engine = AlertEngine()
        var at = warmUp(engine)
        var leaderPos = 2_000.0
        var riderPos = 1_900.0
        // Fall behind while still moving, so the prompt fires with the rider in motion.
        repeat(40) {
            engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 8f))
            leaderPos += 120.0
            riderPos += 40.0
            at += 5.seconds
        }
        val alerts = mutableListOf<Alert>()
        // Stop for 90 s — past the response timeout but short of the incident floor.
        repeat(18) {
            alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 0f)).alerts
            leaderPos += 120.0
            at += 5.seconds
        }
        assertTrue(
            alerts.filterIsInstance<Alert.PossibleIncident>().isEmpty(),
            "a 90 s stop must not alarm the group: $alerts",
        )
        // Keep standing still, and now it should escalate.
        repeat(20) {
            alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 0f)).alerts
            leaderPos += 120.0
            at += 5.seconds
        }
        assertEquals(
            1,
            alerts.filterIsInstance<Alert.PossibleIncident>().size,
            "a rider stopped far longer than any pause, ignoring the prompt, is the real case",
        )
    }

    // ------------------------------- stale while already convincingly stationary

    @Test
    fun `a stationary rider whose phone then dies is signal lost, not an incident`() {
        // The dangerous ordering: they were verifiably parked for minutes, so the motion test is
        // satisfied — only the freshness guard stands between this and a false 'possible crash'.
        val engine = AlertEngine()
        var at = warmUp(engine)
        var leaderPos = 2_000.0
        val riderPos = 1_900.0
        val alerts = mutableListOf<Alert>()
        // Stopped and reporting for 120 s: gap grows past the threshold, prompt fires, and the
        // rider is unambiguously stationary.
        repeat(24) {
            alerts += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 0f)).alerts
            leaderPos += 120.0
            at += 5.seconds
        }
        alerts.clear()
        // Phone dies. Data goes stale while stationarySince is long past the incident floor.
        repeat(30) {
            alerts += engine.assess(tick(at, leaderPos, 0.0, includeRider = false)).alerts
            leaderPos += 120.0
            at += 5.seconds
        }
        assertEquals(1, alerts.filterIsInstance<Alert.SignalLost>().size)
        assertTrue(
            alerts.filterIsInstance<Alert.PossibleIncident>().isEmpty(),
            "we genuinely do not know what happened, and must not claim we do: $alerts",
        )
    }

    // ------------------------------------------------- pausing (issue #7)

    @Test
    fun `pausing a ride does not silence an unanswered question`() {
        // The reason the flag had to be split. A rider stops, is asked whether they are all
        // right, does not answer -- and then the group pauses. Under one coarse "alerts active"
        // switch the escalation died with the pause, which is backwards: pausing should quieten
        // the noisy alert, not the one that matters.
        val engine = AlertEngine()
        var at = warmUp(engine)
        val riderPos = 3_120.0
        var leaderPos = 3_220.0

        val riding = mutableListOf<Alert>()
        repeat(20) {
            riding += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 0f)).alerts
            leaderPos += 100.0
            at += 5.seconds
        }
        assertTrue(
            riding.filterIsInstance<Alert.FallingBehind>().isNotEmpty(),
            "the rider should have been asked while the ride was running: $riding",
        )

        val paused = mutableListOf<Alert>()
        repeat(30) {
            paused += engine.assess(
                tick(at, leaderPos, riderPos, riderSpeed = 0f, state = RoomState.PAUSED),
            ).alerts
            leaderPos += 100.0
            at += 5.seconds
        }
        assertTrue(
            paused.filterIsInstance<Alert.PossibleIncident>().isNotEmpty(),
            "an unanswered prompt must keep escalating through a pause: $paused",
        )
        assertTrue(
            paused.filterIsInstance<Alert.FallingBehind>().isEmpty(),
            "but no *new* separation alert while paused: $paused",
        )
    }

    @Test
    fun `an outstanding question keeps the rider flagged once the ride pauses`() {
        val engine = AlertEngine()
        var at = warmUp(engine)
        val riderPos = 3_120.0
        var leaderPos = 3_220.0
        repeat(20) {
            engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 0f))
            leaderPos += 100.0
            at += 5.seconds
        }

        val paused = engine.assess(
            tick(at, leaderPos, riderPos, riderSpeed = 0f, state = RoomState.PAUSED),
        )
        val assessment = paused.assessments.first { it.riderId == rider.riderId }
        assertTrue(assessment.awaitingResponse, "the question is still outstanding")
        assertEquals(
            RiderStatus.FALLING_BEHIND,
            assessment.status,
            "a rider we asked about and never heard from must not turn green because someone " +
                "hit pause",
        )
    }

    @Test
    fun `gap growth during a pause is not banked against the rider on resume`() {
        // Everyone spreads out around a fuel station, so the gap grows the whole time. If that
        // growth counted, the first tick after resuming would alert on the rider still queueing
        // for the pump -- while they are doing nothing wrong.
        val engine = AlertEngine()
        var at = warmUp(engine)
        val riderPos = 3_120.0
        var leaderPos = 3_220.0

        repeat(40) {
            engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 0f, state = RoomState.PAUSED))
            leaderPos += 150.0
            at += 5.seconds
        }
        assertTrue((leaderPos - riderPos) > 1_500.0, "the gap must already be over the threshold")

        // Ride resumes. The gap keeps rising, but for less than the grace period.
        val justAfterResume = mutableListOf<Alert>()
        repeat(5) {
            justAfterResume += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 0f)).alerts
            leaderPos += 150.0
            at += 5.seconds
        }
        assertTrue(
            justAfterResume.filterIsInstance<Alert.FallingBehind>().isEmpty(),
            "the grace period must start again on resume, not carry over: $justAfterResume",
        )

        // Once the gap has been growing for a full grace period *while riding*, it does alert --
        // the suppression is a reset, not a permanent excuse.
        val later = mutableListOf<Alert>()
        repeat(12) {
            later += engine.assess(tick(at, leaderPos, riderPos, riderSpeed = 0f)).alerts
            leaderPos += 150.0
            at += 5.seconds
        }
        assertTrue(
            later.filterIsInstance<Alert.FallingBehind>().isNotEmpty(),
            "a gap that keeps growing after the resume is still a real fallback: $later",
        )
    }

    @Test
    fun `nobody is reported as losing signal in the lobby`() {
        // In the lobby nobody is publishing a position yet, so "signal lost" would be announcing
        // a problem that does not exist.
        val engine = AlertEngine()
        val lobby = mutableListOf<Alert>()
        lobby += engine.assess(tick(t0, 0.0, 0.0, state = RoomState.LOBBY)).alerts
        lobby += engine.assess(
            tick(t0 + 60.seconds, 0.0, 0.0, state = RoomState.LOBBY, includeRider = false),
        ).alerts
        assertTrue(lobby.isEmpty(), "no alerts before the ride starts: $lobby")

        // The same silence once the ride is running *is* worth reporting.
        val riding = engine.assess(
            tick(t0 + 120.seconds, 0.0, 0.0, state = RoomState.RIDING, includeRider = false),
        ).alerts
        assertTrue(
            riding.filterIsInstance<Alert.SignalLost>().isNotEmpty(),
            "the gate is a room-state check, not a removal: $riding",
        )
    }
}
