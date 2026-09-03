package io.github.meko123456.ridetogether.android.history

import android.content.Context
import io.github.meko123456.ridetogether.summary.RideSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A finished ride, flattened to what the history list shows. */
data class StoredRide(
    val roomId: String,
    val name: String,
    val startedAtMillis: Long,
    val distanceMeters: Double,
    val elapsedMillis: Long,
    val movingMillis: Long,
    val averageMovingSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val stopCount: Int,
    val riderCount: Int,
    val discardedPoints: Int,
)

/**
 * Finished rides, on disk.
 *
 * A JSON file rather than a database for the same reason as everywhere else in these apps: this is
 * a short list that is appended to and read whole. What it deliberately does *not* store is the
 * trace — the positions themselves are the sensitive part, they expire with the room by design
 * (see the privacy policy), and a summary is the only thing worth keeping afterwards.
 */
class RideHistory(private val file: File) {

    /**
     * The real one. Takes a [File] rather than a Context in its primary constructor so the
     * persistence — which is where a bug silently loses a rider's history — can be tested without
     * an emulator.
     */
    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    fun load(): List<StoredRide> {
        val raw = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            StoredRide(
                roomId = row.optString("roomId"),
                name = row.optString("name").ifBlank { "Ride" },
                startedAtMillis = row.optLong("startedAt"),
                distanceMeters = row.optDouble("distance", 0.0),
                elapsedMillis = row.optLong("elapsed"),
                movingMillis = row.optLong("moving"),
                averageMovingSpeedMps = row.optDouble("avgSpeed").takeIf { !it.isNaN() && it > 0 },
                maxSpeedMps = row.optDouble("maxSpeed").takeIf { !it.isNaN() && it > 0 },
                stopCount = row.optInt("stops"),
                riderCount = row.optInt("riders"),
                discardedPoints = row.optInt("discarded"),
            )
        }.sortedByDescending { it.startedAtMillis }
    }

    /** Appends one finished ride. Returns the stored row so the caller can show it immediately. */
    fun save(roomId: String, name: String, summary: RideSummary): StoredRide? {
        val self = summary.riders.firstOrNull() ?: return null
        val stored = StoredRide(
            roomId = roomId,
            name = name,
            startedAtMillis = summary.startedAt?.toEpochMilliseconds() ?: 0L,
            distanceMeters = summary.distanceMeters,
            elapsedMillis = summary.elapsed.inWholeMilliseconds,
            movingMillis = self.movingDuration.inWholeMilliseconds,
            averageMovingSpeedMps = self.averageMovingSpeedMps,
            maxSpeedMps = self.maxSpeedMps,
            stopCount = self.stopCount,
            riderCount = summary.riders.size,
            discardedPoints = self.discardedPoints,
        )
        val all = (listOf(stored) + load()).take(MAX_RIDES)
        val array = JSONArray()
        for (ride in all) {
            array.put(
                JSONObject().apply {
                    put("roomId", ride.roomId)
                    put("name", ride.name)
                    put("startedAt", ride.startedAtMillis)
                    put("distance", ride.distanceMeters)
                    put("elapsed", ride.elapsedMillis)
                    put("moving", ride.movingMillis)
                    put("avgSpeed", ride.averageMovingSpeedMps ?: 0.0)
                    put("maxSpeed", ride.maxSpeedMps ?: 0.0)
                    put("stops", ride.stopCount)
                    put("riders", ride.riderCount)
                    put("discarded", ride.discardedPoints)
                },
            )
        }
        runCatching {
            // Write beside the target and rename: a kill mid-write must not leave a half file.
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(array.toString())
            temp.renameTo(file)
        }
        return stored
    }

    private companion object {
        const val FILE_NAME = "ride-history.json"
        const val MAX_RIDES = 100
    }
}
