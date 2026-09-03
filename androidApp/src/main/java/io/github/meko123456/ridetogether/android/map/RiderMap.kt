package io.github.meko123456.ridetogether.android.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.meko123456.ridetogether.alerts.RiderAssessment
import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.model.RiderStatus
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.geometry.LatLng as MapLatLng

/**
 * The group, on a map.
 *
 * Riders are drawn as a GeoJSON source with a circle layer rather than as individual markers,
 * because a marker per rider means a view per rider to add, move and remove as people join and
 * drop out — while a source is one object whose contents are replaced wholesale on every update,
 * which is exactly the shape the position flow delivers.
 *
 * Colour carries status, and greying a stale rider is the point: a marker that keeps sitting at
 * someone's last known position, looking exactly like a live one, is worse than no marker at all.
 */
@Composable
fun RiderMap(
    positions: Map<String, RiderSample>,
    assessments: List<RiderAssessment>,
    selfId: String,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // MapLibre needs initialising before any MapView exists, and it is safe to call repeatedly.
    remember { MapLibre.getInstance(context) }

    val mapHolder = remember { MapHolder() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).also { view ->
                view.onCreate(null)
                view.getMapAsync { map ->
                    map.setStyle(Style.Builder().fromUri(TILE_STYLE)) { style ->
                        style.addSource(GeoJsonSource(SOURCE_ID))
                        style.addLayer(
                            CircleLayer(LAYER_ID, SOURCE_ID).withProperties(
                                PropertyFactory.circleRadius(9f),
                                // Explicit coercion: a plain string property is not a colour, and
                                // MapLibre silently draws nothing rather than complaining.
                                PropertyFactory.circleColor(
                                    org.maplibre.android.style.expressions.Expression.toColor(
                                        org.maplibre.android.style.expressions.Expression.get("color"),
                                    ),
                                ),
                                PropertyFactory.circleStrokeWidth(2f),
                                PropertyFactory.circleStrokeColor("#ffffff"),
                            ),
                        )
                        mapHolder.map = map
                        mapHolder.ready = true
                        mapHolder.render(positions, assessments, selfId)
                    }
                }
                mapHolder.view = view
                view.onStart()
                view.onResume()
            }
        },
        update = { mapHolder.render(positions, assessments, selfId) },
    )

    DisposableEffect(Unit) {
        onDispose {
            mapHolder.view?.let { view ->
                view.onPause()
                view.onStop()
                view.onDestroy()
            }
            mapHolder.view = null
            mapHolder.map = null
        }
    }
}

/** Holds the map across recompositions and knows whether the style is ready to be written to. */
private class MapHolder {
    var view: MapView? = null
    var map: MapLibreMap? = null
    var ready = false
    private var hasFramedGroup = false

    fun render(
        positions: Map<String, RiderSample>,
        assessments: List<RiderAssessment>,
        selfId: String,
    ) {
        val map = map ?: return
        if (!ready) return
        val style = map.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return

        val statuses = assessments.associate { it.riderId to it.status }
        val features = positions.map { (riderId, sample) ->
            Feature.fromGeometry(
                Point.fromLngLat(sample.location.longitude, sample.location.latitude),
            ).apply {
                addStringProperty("color", colourFor(riderId == selfId, statuses[riderId]))
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
        android.util.Log.i("RiderMap", "drew ${features.size} riders")

        // Frame the group once, rather than on every update: a camera that re-fits itself every
        // few seconds is unusable, and a rider who has panned the map away wants it to stay there.
        if (!hasFramedGroup && positions.size >= 1) {
            frame(map, positions)
            hasFramedGroup = true
        }
    }

    private fun frame(map: MapLibreMap, positions: Map<String, RiderSample>) {
        val points = positions.values.map { MapLatLng(it.location.latitude, it.location.longitude) }
        if (points.isEmpty()) return
        if (points.size == 1) {
            map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                .target(points.first())
                .zoom(14.0)
                .build()
            return
        }
        val bounds = LatLngBounds.Builder().includes(points).build()
        map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96), 600)
    }

    /**
     * Status as colour. A stale rider goes grey deliberately — the same decision the alert engine
     * makes when it refuses to call signal loss an incident, carried onto the map.
     */
    private fun colourFor(isSelf: Boolean, status: RiderStatus?): String = when (status) {
        RiderStatus.POSSIBLE_INCIDENT -> "#d64545"
        RiderStatus.FALLING_BEHIND -> "#e8a33d"
        RiderStatus.SIGNAL_LOST -> "#9aa0a6"
        RiderStatus.STOPPED -> "#5b8def"
        RiderStatus.ACTIVE, null -> if (isSelf) "#1b5e9c" else "#2e7d32"
    }
}

/**
 * The tile source, which took two attempts and is worth writing down.
 *
 * **Not OpenStreetMap's public tile server.** Its tile usage policy forbids precisely this — an
 * app pointing users at tile.openstreetmap.org — so however convenient, it is not an option.
 *
 * **Not MapLibre's demo tiles either**, which is where this started. They render, and on a phone
 * that looks like success: attribution appears, the water draws. But they carry no data above
 * roughly zoom 5, so framing the group at zoom 14 produced an empty blue rectangle — a map with
 * no map on it. For an app whose entire purpose is watching riders on streets, a world-scale
 * basemap is not a placeholder, it is the wrong thing.
 *
 * OpenFreeMap serves OSM-derived vector tiles with no API key and is intended for public use,
 * which makes it the only option here that needs no secret in the repository. A shipping app
 * should still plan to self-host or pay for a provider rather than depend on someone else's
 * generosity for a safety feature.
 */
private const val TILE_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val SOURCE_ID = "riders"
private const val LAYER_ID = "rider-circles"
