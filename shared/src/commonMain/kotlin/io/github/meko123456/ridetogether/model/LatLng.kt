package io.github.meko123456.ridetogether.model

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * A WGS-84 coordinate. Kept as a plain value type with no platform dependency so the alert
 * engine — and its tests — never need a maps SDK.
 */
data class LatLng(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }
}

/** Great-circle geometry. Distances are metres; bearings are degrees clockwise from north. */
object Geo {

    /** Mean Earth radius (metres), the usual haversine constant. */
    const val EARTH_RADIUS_M: Double = 6_371_008.8

    /**
     * Great-circle ("as the crow flies") distance in metres.
     *
     * Haversine rather than the cheaper equirectangular approximation: a ride can span enough
     * latitude that the flat approximation drifts, and this is not on a hot path — it runs a
     * handful of times per location update.
     */
    fun distanceMeters(from: LatLng, to: LatLng): Double {
        val dLat = (to.latitude - from.latitude).toRadians()
        val dLon = (to.longitude - from.longitude).toRadians()
        val lat1 = from.latitude.toRadians()
        val lat2 = to.latitude.toRadians()
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [from] to [to], in degrees 0..360 clockwise from north. */
    fun bearingDegrees(from: LatLng, to: LatLng): Double {
        val lat1 = from.latitude.toRadians()
        val lat2 = to.latitude.toRadians()
        val dLon = (to.longitude - from.longitude).toRadians()
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = atan2(y, x).toDegrees()
        return (deg + 360.0) % 360.0
    }

    /**
     * Cumulative distance along a polyline, in metres. Index i holds the distance from the start
     * to vertex i, so index 0 is always 0 and the last entry is the route length.
     */
    fun cumulativeDistances(route: List<LatLng>): List<Double> {
        if (route.isEmpty()) return emptyList()
        val out = ArrayList<Double>(route.size)
        var acc = 0.0
        out += 0.0
        for (i in 1 until route.size) {
            acc += distanceMeters(route[i - 1], route[i])
            out += acc
        }
        return out
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
    private fun Double.toDegrees(): Double = this * 180.0 / PI
}
