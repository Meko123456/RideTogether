package io.github.meko123456.ridetogether.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeoTest {

    private val origin = LatLng(0.0, 0.0)

    @Test
    fun `distance to itself is zero`() {
        assertEquals(0.0, Geo.distanceMeters(origin, origin), 0.001)
    }

    @Test
    fun `one degree of latitude is about 111 km anywhere`() {
        // A degree of latitude is constant; a degree of longitude only equals it at the equator.
        assertEquals(111_195.0, Geo.distanceMeters(origin, LatLng(1.0, 0.0)), 200.0)
        assertEquals(111_195.0, Geo.distanceMeters(LatLng(45.0, 10.0), LatLng(46.0, 10.0)), 200.0)
    }

    @Test
    fun `a degree of longitude shrinks with latitude`() {
        val atEquator = Geo.distanceMeters(origin, LatLng(0.0, 1.0))
        val atSixty = Geo.distanceMeters(LatLng(60.0, 0.0), LatLng(60.0, 1.0))
        assertEquals(111_195.0, atEquator, 200.0)
        // cos(60°) = 0.5, so a degree of longitude is half as wide there.
        assertEquals(atEquator / 2, atSixty, 500.0)
    }

    @Test
    fun `antipodal points are half the circumference apart`() {
        assertEquals(20_015_000.0, Geo.distanceMeters(origin, LatLng(0.0, 180.0)), 5_000.0)
    }

    @Test
    fun `bearing points north east south and west`() {
        assertEquals(0.0, Geo.bearingDegrees(origin, LatLng(1.0, 0.0)), 0.5)
        assertEquals(90.0, Geo.bearingDegrees(origin, LatLng(0.0, 1.0)), 0.5)
        assertEquals(180.0, Geo.bearingDegrees(origin, LatLng(-1.0, 0.0)), 0.5)
        assertEquals(270.0, Geo.bearingDegrees(origin, LatLng(0.0, -1.0)), 0.5)
    }

    @Test
    fun `bearing is normalised into 0 until 360`() {
        val b = Geo.bearingDegrees(LatLng(10.0, 10.0), LatLng(9.0, 9.0))
        assertTrue(b in 0.0..360.0, "bearing out of range: $b")
    }

    @Test
    fun `cumulative distances start at zero and end at route length`() {
        val route = listOf(origin, LatLng(0.0, 1.0), LatLng(0.0, 2.0))
        val cum = Geo.cumulativeDistances(route)
        assertEquals(3, cum.size)
        assertEquals(0.0, cum[0], 0.001)
        assertEquals(cum[1] * 2, cum[2], 1.0)
        assertEquals(Geo.distanceMeters(origin, LatLng(0.0, 2.0)), cum[2], 5.0)
    }

    @Test
    fun `an empty route has no distances and a single point has one`() {
        assertTrue(Geo.cumulativeDistances(emptyList()).isEmpty())
        assertEquals(listOf(0.0), Geo.cumulativeDistances(listOf(origin)))
    }

    @Test
    fun `out-of-range coordinates are rejected`() {
        assertFailsWith<IllegalArgumentException> { LatLng(91.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { LatLng(0.0, 181.0) }
        assertFailsWith<IllegalArgumentException> { LatLng(-90.1, 0.0) }
    }
}
