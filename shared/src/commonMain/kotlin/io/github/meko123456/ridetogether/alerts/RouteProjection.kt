package io.github.meko123456.ridetogether.alerts

import io.github.meko123456.ridetogether.model.Geo
import io.github.meko123456.ridetogether.model.LatLng
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * Where a rider sits along the planned route.
 *
 * @property progressMeters distance from the route start to the nearest point on the route
 * @property offRouteMeters perpendicular distance from the rider to the route
 */
data class RoutePosition(val progressMeters: Double, val offRouteMeters: Double)

/**
 * Projects positions onto the leader's planned polyline, which is what turns "how far apart are
 * these two riders" into the only question that matters on a road: **how far back along the
 * route**. Two riders 300 m apart as the crow flies can be 4 km apart on a switchback, and a
 * rider on the opposite carriageway is not behind at all.
 *
 * The maths is done in a local metres frame per segment (equirectangular about the segment
 * start). Over a polyline segment — tens or hundreds of metres — that is accurate to well under
 * a metre, and unlike a full geodesic projection it stays cheap enough to run for every rider on
 * every location update.
 */
object RouteProjection {

    /** Beyond this, treat the rider as not on the route at all and fall back to straight lines. */
    const val DEFAULT_OFF_ROUTE_TOLERANCE_M: Double = 250.0

    private const val METRES_PER_DEGREE_LAT = 110_574.0
    private const val METRES_PER_DEGREE_LON_EQUATOR = 111_320.0

    /**
     * Nearest point on [route] to [point]. Returns null for an empty route; a single-vertex
     * route yields progress 0 and the straight-line distance to that vertex.
     */
    fun project(point: LatLng, route: List<LatLng>): RoutePosition? {
        if (route.isEmpty()) return null
        if (route.size == 1) return RoutePosition(0.0, Geo.distanceMeters(point, route[0]))

        var best = RoutePosition(0.0, Double.MAX_VALUE)
        var travelled = 0.0
        for (i in 0 until route.size - 1) {
            val a = route[i]
            val b = route[i + 1]
            val segmentLength = Geo.distanceMeters(a, b)
            val (alongSegment, perpendicular) = projectOntoSegment(point, a, b, segmentLength)
            if (perpendicular < best.offRouteMeters) {
                best = RoutePosition(travelled + alongSegment, perpendicular)
            }
            travelled += segmentLength
        }
        return best
    }

    /**
     * @return (distance along the segment from [a], perpendicular distance to the segment)
     */
    private fun projectOntoSegment(
        point: LatLng,
        a: LatLng,
        b: LatLng,
        segmentLength: Double,
    ): Pair<Double, Double> {
        if (segmentLength == 0.0) return 0.0 to Geo.distanceMeters(point, a)
        // Local metres frame anchored at `a`. Longitude shrinks by cos(latitude).
        val lonScale = METRES_PER_DEGREE_LON_EQUATOR * cos(a.latitude * PI / 180.0)
        val px = (point.longitude - a.longitude) * lonScale
        val py = (point.latitude - a.latitude) * METRES_PER_DEGREE_LAT
        val bx = (b.longitude - a.longitude) * lonScale
        val by = (b.latitude - a.latitude) * METRES_PER_DEGREE_LAT

        val lengthSquared = bx * bx + by * by
        if (lengthSquared == 0.0) return 0.0 to sqrt(px * px + py * py)
        // Clamped so a rider before the start or past the end projects onto the endpoint.
        val t = ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
        val cx = t * bx
        val cy = t * by
        val perpendicular = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
        return t * segmentLength to perpendicular
    }
}
