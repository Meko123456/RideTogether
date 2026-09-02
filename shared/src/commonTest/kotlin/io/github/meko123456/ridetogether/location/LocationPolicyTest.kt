package io.github.meko123456.ridetogether.location

import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.model.RoomState
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LocationPolicyTest {

    private val t0 = Instant.parse("2026-09-02T09:00:00Z")

    private fun conditions(
        state: RoomState = RoomState.RIDING,
        speedMps: Double? = 25.0,
        batteryPercent: Int? = 80,
    ) = LocationConditions(state, speedMps, batteryPercent)

    private fun east(meters: Double) = LatLng(0.0, meters / 111_320.0)

    // ─────────────────────────────── the kill switch

    @Test
    fun `no interval at all outside a ride - the kill switch`() {
        assertNull(LocationPolicy.intervalFor(conditions(state = RoomState.LOBBY)))
        assertNull(LocationPolicy.intervalFor(conditions(state = RoomState.ENDED)))
    }

    @Test
    fun `a paused ride still reports because the group is still on the map`() {
        // Pausing suppresses the nagging, not the sharing — and the rider agreed to be visible
        // for the ride, which is not over.
        assertEquals(
            LocationPolicy.STOPPED_INTERVAL,
            LocationPolicy.intervalFor(conditions(state = RoomState.PAUSED, speedMps = 0.0)),
        )
    }

    @Test
    fun `every state that shares location gets an interval and every other one gets none`() {
        // Stated as a loop so a new RoomState cannot quietly default to collecting location.
        for (state in RoomState.entries) {
            val interval = LocationPolicy.intervalFor(conditions(state = state))
            assertEquals(
                state.sharesLocation,
                interval != null,
                "$state shares=${state.sharesLocation} but interval=$interval",
            )
        }
    }

    // ─────────────────────────────── movement bands

    @Test
    fun `riding gets the fast interval`() {
        assertEquals(LocationPolicy.MOVING_INTERVAL, LocationPolicy.intervalFor(conditions(speedMps = 25.0)))
    }

    @Test
    fun `parked gets the slow interval`() {
        assertEquals(LocationPolicy.STOPPED_INTERVAL, LocationPolicy.intervalFor(conditions(speedMps = 0.0)))
    }

    @Test
    fun `city traffic is not treated as parked`() {
        // The gap in the spec: it describes "> 20 km/h" and "stopped" and nothing between, and
        // 15 km/h through town is neither. At the stopped interval that is 125 m between fixes.
        val trafficInterval = LocationPolicy.intervalFor(conditions(speedMps = 4.0))
        assertEquals(LocationPolicy.SLOW_INTERVAL, trafficInterval)
        assertTrue(trafficInterval!! < LocationPolicy.STOPPED_INTERVAL)
        assertTrue(trafficInterval > LocationPolicy.MOVING_INTERVAL)
    }

    @Test
    fun `the first fix of a ride is reported quickly even with no speed to go on`() {
        // The engine needs several positions before it can judge anything, and the start of a
        // ride is the cheapest moment to be generous.
        assertEquals(LocationPolicy.MOVING_INTERVAL, LocationPolicy.intervalFor(conditions(speedMps = null)))
    }

    @Test
    fun `the band boundaries fall on the side that reports more often`() {
        // Exactly 20 km/h counts as moving, and a shade above stationary counts as slow. When in
        // doubt about a rider's state, the cheaper mistake is a fix too many.
        assertEquals(
            LocationPolicy.MOVING_INTERVAL,
            LocationPolicy.intervalFor(conditions(speedMps = LocationPolicy.MOVING_SPEED_MPS)),
        )
        assertEquals(
            LocationPolicy.SLOW_INTERVAL,
            LocationPolicy.intervalFor(conditions(speedMps = LocationPolicy.STATIONARY_SPEED_MPS + 0.01)),
        )
        assertEquals(
            LocationPolicy.STOPPED_INTERVAL,
            LocationPolicy.intervalFor(conditions(speedMps = LocationPolicy.STATIONARY_SPEED_MPS)),
        )
    }

    // ─────────────────────────────── battery

    @Test
    fun `a low battery slows a moving rider down`() {
        val healthy = LocationPolicy.intervalFor(conditions(speedMps = 25.0, batteryPercent = 80))
        val dying = LocationPolicy.intervalFor(conditions(speedMps = 25.0, batteryPercent = 12))
        assertEquals(LocationPolicy.MOVING_INTERVAL, healthy)
        assertEquals(LocationPolicy.LOW_BATTERY_MINIMUM_INTERVAL, dying)
        assertTrue(dying!! > healthy!!, "battery saving has to mean fewer fixes, not more")
    }

    @Test
    fun `a low battery never speeds anyone up`() {
        // The spec's "battery < 20% → every 15 s", read literally, makes a *stopped* rider on a
        // dying battery report twice as often as a healthy one — the rule doubling the drain it
        // exists to prevent. It is a cap, not a floor.
        for (speed in listOf(0.0, 1.0, 3.0, 10.0, 30.0, null)) {
            val healthy = LocationPolicy.intervalFor(conditions(speedMps = speed, batteryPercent = 90))!!
            val dying = LocationPolicy.intervalFor(conditions(speedMps = speed, batteryPercent = 5))!!
            assertTrue(dying >= healthy, "at speed $speed: dying=$dying healthy=$healthy")
        }
    }

    @Test
    fun `a stopped rider on a dying battery keeps the slower of the two intervals`() {
        assertEquals(
            LocationPolicy.STOPPED_INTERVAL,
            LocationPolicy.intervalFor(conditions(speedMps = 0.0, batteryPercent = 3)),
        )
    }

    @Test
    fun `battery saving is announced only when the battery is actually low`() {
        assertTrue(LocationPolicy.isBatterySaver(19))
        assertFalse(LocationPolicy.isBatterySaver(LocationPolicy.LOW_BATTERY_PERCENT))
        assertFalse(LocationPolicy.isBatterySaver(80))
    }

    @Test
    fun `an unreadable battery is not treated as an empty one`() {
        assertFalse(LocationPolicy.isBatterySaver(null))
        assertEquals(
            LocationPolicy.MOVING_INTERVAL,
            LocationPolicy.intervalFor(conditions(speedMps = 25.0, batteryPercent = null)),
        )
    }

    // ─────────────────────────────── what reaches the network

    @Test
    fun `the first fix always goes out`() {
        assertTrue(
            LocationPolicy.shouldPublish(null, null, east(0.0), t0, LocationPolicy.MOVING_INTERVAL),
        )
    }

    @Test
    fun `a fix inside the interval and close by is dropped`() {
        // The provider delivers more often than asked; this is what keeps that off the network.
        assertFalse(
            LocationPolicy.shouldPublish(
                previous = east(0.0),
                previousAt = t0,
                candidate = east(10.0),
                at = t0 + 1.seconds,
                interval = LocationPolicy.MOVING_INTERVAL,
            ),
        )
    }

    @Test
    fun `a fix once the interval has elapsed goes out even if the rider has not moved`() {
        assertTrue(
            LocationPolicy.shouldPublish(
                previous = east(0.0),
                previousAt = t0,
                candidate = east(0.0),
                at = t0 + LocationPolicy.MOVING_INTERVAL,
                interval = LocationPolicy.MOVING_INTERVAL,
            ),
        )
    }

    @Test
    fun `a big jump is not hidden behind the timer`() {
        // A rider pulling away from a light covers 75 m long before a 30-second stopped interval
        // expires, and the group should see it.
        assertTrue(
            LocationPolicy.shouldPublish(
                previous = east(0.0),
                previousAt = t0,
                candidate = east(LocationPolicy.SIGNIFICANT_MOVE_METERS + 5),
                at = t0 + 2.seconds,
                interval = LocationPolicy.STOPPED_INTERVAL,
            ),
        )
    }

    @Test
    fun `the interval the policy chose is the interval publishing respects`() {
        // A stopped rider on the 30 s interval must not have their fixes let through at 4 s just
        // because some other part of the app asked for the moving interval.
        val stopped = LocationPolicy.intervalFor(conditions(speedMps = 0.0))!!
        assertFalse(
            LocationPolicy.shouldPublish(east(0.0), t0, east(5.0), t0 + 10.seconds, stopped),
        )
        assertTrue(
            LocationPolicy.shouldPublish(east(0.0), t0, east(5.0), t0 + 31.seconds, stopped),
        )
    }
}
