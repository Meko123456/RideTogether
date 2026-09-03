package io.github.meko123456.ridetogether.summary

import io.github.meko123456.ridetogether.model.LatLng
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Traces run due east along the equator, where 0.001° of longitude is ~111.32 m, so the intended
 * distances are readable in the test itself.
 */
class RideSummariserTest {

    private val t0 = Instant.parse("2026-09-01T08:00:00Z")
    private val summariser = RideSummariser()

    private fun east(meters: Double) = LatLng(0.0, meters / 111_320.0)

    /** A steady run: [seconds] fixes one second apart at [speed] m/s. */
    private fun steady(speed: Double, seconds: Int, from: Instant = t0, startAt: Double = 0.0): List<TracePoint> {
        var position = startAt
        var at = from
        return buildList {
            repeat(seconds) {
                add(TracePoint(at, east(position), speed))
                position += speed
                at += 1.seconds
            }
        }
    }

    private fun assertClose(expected: Double, actual: Double?, tolerance: Double, label: String) {
        assertTrue(actual != null, "$label was null")
        assertTrue(abs(expected - actual) <= tolerance, "$label: expected ~$expected but was $actual")
    }

    @Test
    fun `a steady run reports the distance it covered`() {
        val summary = summariser.summarise("room", mapOf("alex" to steady(speed = 20.0, seconds = 60)))
        val rider = summary.riders.single()
        // 59 one-second steps at 20 m/s.
        assertClose(1_180.0, rider.distanceMeters, 5.0, "distance")
        assertClose(20.0, rider.averageMovingSpeedMps, 0.5, "moving average")
        assertClose(20.0, rider.maxSpeedMps, 0.5, "max speed")
        assertEquals(0, rider.stopCount)
        assertEquals(0, rider.discardedPoints)
    }

    @Test
    fun `a lunch stop does not drag the average speed down`() {
        // 5 minutes riding, 20 minutes stopped, 5 minutes riding. Wall-clock average would be
        // about a third of the real riding speed, which is the number riders do not believe.
        val first = steady(speed = 20.0, seconds = 300)
        val stopStart = t0 + 300.seconds
        val parked = buildList {
            var at = stopStart
            repeat(20) {
                add(TracePoint(at, east(6_000.0), 0.0))
                at += 1.minutes
            }
        }
        val second = steady(speed = 20.0, seconds = 300, from = stopStart + 20.minutes, startAt = 6_000.0)

        val rider = summariser.summarise("room", mapOf("alex" to (first + parked + second))).riders.single()

        assertClose(20.0, rider.averageMovingSpeedMps, 1.0, "moving average excludes the stop")
        assertEquals(1, rider.stopCount, "one stop, not twenty stationary fixes")
        assertTrue(rider.stoppedDuration >= 19.minutes, "and the stopped time is reported honestly")
        assertTrue(
            rider.movingDuration < rider.stoppedDuration,
            "sanity: this ride really was mostly stopped",
        )
    }

    @Test
    fun `a wait at a traffic light is not counted as a stop`() {
        val riding = steady(speed = 20.0, seconds = 60)
        var at = t0 + 60.seconds
        val light = buildList {
            repeat(40) {
                add(TracePoint(at, east(1_200.0), 0.0))
                at += 1.seconds
            }
        }
        // The rider pulls away again, which is what makes it a light rather than a stop -- and
        // which is what puts the threshold check on the path being tested.
        val onwards = steady(speed = 20.0, seconds = 30, from = at, startAt = 1_200.0)

        val rider = summariser.summarise("room", mapOf("alex" to (riding + light + onwards))).riders.single()
        assertEquals(0, rider.stopCount, "40 seconds is a light - not a stop worth listing")
        assertTrue(rider.stoppedDuration >= 39.seconds, "but the time still shows up as stopped")
    }

    @Test
    fun `a ride that finishes parked counts that last stop`() {
        val riding = steady(speed = 20.0, seconds = 60)
        var at = t0 + 60.seconds
        val parked = buildList {
            repeat(90) {
                add(TracePoint(at, east(1_200.0), 0.0))
                at += 1.seconds
            }
        }
        val rider = summariser.summarise("room", mapOf("alex" to (riding + parked))).riders.single()
        assertEquals(1, rider.stopCount, "the ride ended at the cafe - that is a stop")
    }

    @Test
    fun `an exactly duplicated fix carries no information and is discarded`() {
        // Same timestamp AND same place. Without an explicit guard this divides zero by zero,
        // and a NaN in the speed series is a much worse outcome than one dropped point.
        val trace = steady(speed = 20.0, seconds = 10).toMutableList()
        trace.add(3, trace[3].copy(speedMps = null))

        val rider = summariser.summarise("room", mapOf("alex" to trace)).riders.single()
        assertEquals(1, rider.discardedPoints)
        assertClose(20.0, rider.maxSpeedMps, 1.0, "max speed stays a real number")
        assertTrue(rider.distanceMeters > 0.0, "and the rest of the trace still counts")
    }

    @Test
    fun `a phone sitting still does not accumulate a ride out of GPS drift`() {
        // The bug a real device found. Four fixes thirty seconds apart from a stationary phone,
        // each drifting ~35 m, produced 105 m of "ride" with an average of 20 km/h and a top
        // speed of 1 km/h — an average above the maximum, which cannot happen.
        var at = t0
        var offset = 0.0
        val drifting = buildList {
            repeat(5) { index ->
                add(TracePoint(at, east(offset), 0.3))
                offset += if (index % 2 == 0) 35.0 else -33.0
                at += 30.seconds
            }
        }
        val rider = summariser.summarise("room", mapOf("alex" to drifting)).riders.single()

        assertTrue(rider.distanceMeters < 5.0, "drift is not distance, got ${rider.distanceMeters}")
        assertTrue(rider.stoppedDuration >= 100.seconds, "it was stopped the whole time")
        val average = rider.averageMovingSpeedMps
        val top = rider.maxSpeedMps
        if (average != null && top != null) {
            assertTrue(average <= top + 0.1, "average $average cannot exceed top $top")
        }
    }

    @Test
    fun `a trace with no reported speeds is still a ride`() {
        // Providers do omit speed — older hardware, and the first fix after a cold start. If a
        // missing reading counted as "not moving", every such ride would come out as zero
        // distance. Displacement is all there is to go on, so it is enough on its own.
        var at = t0
        var position = 0.0
        val silent = buildList {
            repeat(60) {
                add(TracePoint(at, east(position), null))
                position += 20.0
                at += 1.seconds
            }
        }
        val rider = summariser.summarise("room", mapOf("alex" to silent)).riders.single()
        assertClose(1_180.0, rider.distanceMeters, 5.0, "distance")
        assertTrue(rider.movingDuration >= 55.seconds, "was ${rider.movingDuration}")
        assertClose(20.0, rider.averageMovingSpeedMps, 1.0, "moving average")
    }

    @Test
    fun `an average can never exceed the top speed on a real trace either`() {
        // The invariant the bug violated, checked on an ordinary ride.
        val trace = steady(speed = 20.0, seconds = 120) +
            steady(speed = 8.0, seconds = 60, from = t0 + 120.seconds, startAt = 2_400.0)
        val rider = summariser.summarise("room", mapOf("alex" to trace)).riders.single()
        val average = requireNotNull(rider.averageMovingSpeedMps)
        val top = requireNotNull(rider.maxSpeedMps)
        assertTrue(average <= top + 0.1, "average $average exceeded top $top")
    }

    @Test
    fun `a teleporting fix does not add distance`() {
        val trace = steady(speed = 20.0, seconds = 60).toMutableList()
        // One fix 40 km away, then straight back. Taken literally this adds 80 km.
        trace.add(TracePoint(t0 + 60.seconds, east(41_200.0), null))
        trace.add(TracePoint(t0 + 61.seconds, east(1_220.0), 20.0))

        val rider = summariser.summarise("room", mapOf("alex" to trace)).riders.single()
        assertTrue(
            rider.distanceMeters < 1_300.0,
            "the glitch must not be counted, got ${rider.distanceMeters}",
        )
        assertEquals(2, rider.discardedPoints, "and it must be reported, not silently dropped")
    }

    @Test
    fun `a lone bad fix does not become the maximum speed`() {
        // A single 200 km/h sample in an otherwise 20 m/s trace. Both its neighbours are honest,
        // so the median filter outvotes it.
        val trace = steady(speed = 20.0, seconds = 60).toMutableList()
        trace[30] = trace[30].copy(speedMps = 55.0)

        val rider = summariser.summarise("room", mapOf("alex" to trace)).riders.single()
        assertClose(20.0, rider.maxSpeedMps, 1.0, "max speed")
    }

    @Test
    fun `a genuinely fast stretch is reported`() {
        // Sustained across many fixes, so it is not noise and must survive the filter.
        val trace = steady(speed = 20.0, seconds = 30) +
            steady(speed = 44.0, seconds = 30, from = t0 + 30.seconds, startAt = 600.0)
        val rider = summariser.summarise("room", mapOf("alex" to trace)).riders.single()
        assertClose(44.0, rider.maxSpeedMps, 1.0, "max speed")
    }

    @Test
    fun `a coverage hole is not ridden through in a straight line`() {
        // Out of signal for five minutes through a mountain tunnel, 8 km apart either side.
        val before = steady(speed = 20.0, seconds = 30)
        val after = steady(speed = 20.0, seconds = 30, from = t0 + 5.minutes + 30.seconds, startAt = 8_600.0)
        val rider = summariser.summarise("room", mapOf("alex" to (before + after))).riders.single()
        assertTrue(
            rider.distanceMeters < 1_400.0,
            "the hole must not be claimed as distance, got ${rider.distanceMeters}",
        )
        assertEquals(1, rider.discardedPoints)
    }

    @Test
    fun `each rider gets their own numbers and the ride keeps the longest`() {
        val leader = steady(speed = 20.0, seconds = 300)
        val latecomer = steady(speed = 20.0, seconds = 100, from = t0 + 200.seconds, startAt = 4_000.0)

        val summary = summariser.summarise("room", mapOf("merab" to leader, "nika" to latecomer))
        assertEquals(2, summary.riders.size)
        assertEquals("merab", summary.riders.first().riderId, "sorted by distance ridden")
        assertClose(summary.riders.first().distanceMeters, summary.distanceMeters, 0.001, "ride distance")
        assertTrue(
            summary.riders.last().distanceMeters < summary.distanceMeters,
            "a rider who joined late rode less, and both numbers are true",
        )
        assertEquals(t0, summary.startedAt)
        // 300 fixes one second apart span 299 seconds, not 300.
        assertEquals(299.seconds, summary.elapsed)
    }

    @Test
    fun `a ride with no trace at all summarises to nothing`() {
        val summary = summariser.summarise("room", emptyMap())
        assertTrue(summary.isEmpty)
        assertEquals(0.0, summary.distanceMeters)
        assertNull(summary.startedAt)
    }

    @Test
    fun `a single fix is not enough to claim any distance`() {
        val summary = summariser.summarise("room", mapOf("alex" to listOf(TracePoint(t0, east(0.0), 20.0))))
        val rider = summary.riders.single()
        assertEquals(0.0, rider.distanceMeters)
        assertNull(rider.averageMovingSpeedMps, "no elapsed moving time means no average to report")
        assertEquals(0, rider.stopCount)
    }

    @Test
    fun `points arriving out of order are sorted rather than believed`() {
        val ordered = steady(speed = 20.0, seconds = 60)
        val shuffled = ordered.reversed()
        val fromOrdered = summariser.summarise("room", mapOf("alex" to ordered)).riders.single()
        val fromShuffled = summariser.summarise("room", mapOf("alex" to shuffled)).riders.single()
        assertClose(fromOrdered.distanceMeters, fromShuffled.distanceMeters, 0.001, "distance")
        assertEquals(fromOrdered.movingDuration, fromShuffled.movingDuration)
    }

    @Test
    fun `a duplicate timestamp is discarded rather than dividing by zero`() {
        val trace = steady(speed = 20.0, seconds = 10).toMutableList()
        trace.add(2, trace[2].copy(location = east(60.0)))
        val rider = summariser.summarise("room", mapOf("alex" to trace)).riders.single()
        assertEquals(1, rider.discardedPoints)
        assertTrue(rider.maxSpeedMps!! < 30.0, "no infinite speed from a zero-length interval")
    }

    @Test
    fun `a fix that reports a riding speed after a silent minute is still stopped time`() {
        // The case that caught this out. A rider parked with reporting slowed to once a minute
        // pulls away, and the first fix says 20 m/s while the bike has not moved at all. Booking
        // that minute as riding is how a lunch stop leaks into the moving average.
        val trace = listOf(
            TracePoint(t0, east(0.0), 0.0),
            TracePoint(t0 + 1.minutes, east(0.0), 20.0),
        ) + steady(speed = 20.0, seconds = 60, from = t0 + 1.minutes + 1.seconds, startAt = 20.0)

        val rider = summariser.summarise("room", mapOf("alex" to trace)).riders.single()
        assertTrue(
            rider.stoppedDuration >= 1.minutes,
            "the silent minute belongs to the stop, got ${rider.stoppedDuration}",
        )
        assertClose(20.0, rider.averageMovingSpeedMps, 1.0, "moving average")
    }
}
