package io.github.meko123456.ridetogether.alerts

import io.github.meko123456.ridetogether.model.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteProjectionTest {

    /** A straight 10 km run due east along the equator: 0.001° ≈ 111.32 m. */
    private fun east(meters: Double) = LatLng(0.0, meters / 111_320.0)
    private fun north(meters: Double, eastMeters: Double = 0.0) =
        LatLng(meters / 110_574.0, eastMeters / 111_320.0)

    private val straightRoute = listOf(east(0.0), east(5_000.0), east(10_000.0))

    @Test
    fun `an empty route projects nothing`() {
        assertNull(RouteProjection.project(east(100.0), emptyList()))
    }

    @Test
    fun `a single-point route reports the straight-line distance to it`() {
        val p = RouteProjection.project(east(500.0), listOf(east(0.0)))
        assertNotNull(p)
        assertEquals(0.0, p.progressMeters, 0.001)
        assertEquals(500.0, p.offRouteMeters, 5.0)
    }

    @Test
    fun `a point on the route reports its distance along and no offset`() {
        val p = RouteProjection.project(east(2_500.0), straightRoute)
        assertNotNull(p)
        assertEquals(2_500.0, p.progressMeters, 5.0)
        assertEquals(0.0, p.offRouteMeters, 1.0)
    }

    @Test
    fun `progress accumulates across segments`() {
        val p = RouteProjection.project(east(7_500.0), straightRoute)
        assertNotNull(p)
        assertEquals(7_500.0, p.progressMeters, 10.0)
    }

    @Test
    fun `a point beside the route keeps its progress and reports the offset`() {
        // 200 m north of the 3 km mark.
        val p = RouteProjection.project(north(200.0, eastMeters = 3_000.0), straightRoute)
        assertNotNull(p)
        assertEquals(3_000.0, p.progressMeters, 10.0)
        assertEquals(200.0, p.offRouteMeters, 5.0)
    }

    @Test
    fun `a point before the start clamps to the start`() {
        val p = RouteProjection.project(east(-500.0), straightRoute)
        assertNotNull(p)
        assertEquals(0.0, p.progressMeters, 1.0)
        assertEquals(500.0, p.offRouteMeters, 5.0)
    }

    @Test
    fun `a point past the end clamps to the end`() {
        val p = RouteProjection.project(east(12_000.0), straightRoute)
        assertNotNull(p)
        // The polyline's measured (haversine) length is marginally under the nominal 10 km,
        // hence the looser tolerance: what matters is that it clamps to the end, not the metre.
        assertEquals(10_000.0, p.progressMeters, 25.0)
        assertEquals(2_000.0, p.offRouteMeters, 25.0)
    }

    @Test
    fun `on a hairpin, two riders close together can be far apart along the route`() {
        // Out 3 km east, back 3 km west 100 m to the north — a switchback. Two riders 100 m
        // apart across the hairpin are ~6 km apart along the road, which is the entire reason
        // gaps are measured along the route rather than as the crow flies.
        val hairpin = listOf(east(0.0), east(3_000.0), north(100.0, eastMeters = 3_000.0), north(100.0, eastMeters = 0.0))
        val outbound = RouteProjection.project(east(1_000.0), hairpin)
        val inbound = RouteProjection.project(north(100.0, eastMeters = 1_000.0), hairpin)
        assertNotNull(outbound)
        assertNotNull(inbound)
        assertEquals(1_000.0, outbound.progressMeters, 20.0)
        // 3 km out + 100 m across + 2 km back = ~5.1 km along the route.
        assertEquals(5_100.0, inbound.progressMeters, 50.0)
        assertTrue(inbound.progressMeters - outbound.progressMeters > 4_000.0)
    }

    @Test
    fun `a zero-length segment is handled without dividing by zero`() {
        val degenerate = listOf(east(0.0), east(0.0), east(1_000.0))
        val p = RouteProjection.project(east(400.0), degenerate)
        assertNotNull(p)
        assertEquals(400.0, p.progressMeters, 5.0)
    }
}
