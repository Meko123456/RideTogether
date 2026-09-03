package io.github.meko123456.ridetogether.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.meko123456.ridetogether.android.history.StoredRide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Distance the way a rider says it: metres under a kilometre, one decimal above. */
fun formatDistance(meters: Double): String =
    if (meters < 1_000) "${meters.toInt()} m" else "%.1f km".format(meters / 1_000)

/** `1h 24m`, or `7m` — never `0h 07m`, which reads like a clock rather than a duration. */
fun formatDuration(millis: Long): String {
    val seconds = millis / 1_000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m"
        // Under a minute reads as "0m" otherwise, which looks like a failure rather than a short
        // ride — and a short ride is exactly what a first test run produces.
        else -> "${seconds}s"
    }
}

fun formatSpeed(mps: Double?): String = mps?.let { "${(it * 3.6).toInt()} km/h" } ?: "—"

/**
 * What a ride was, once it is over.
 *
 * The moving average is shown rather than the wall-clock one, and stopped time is shown beside it,
 * for the reason `RideSummariser` documents: an average that includes a lunch stop reads as though
 * the app mis-measured the ride. The discarded-fix count is shown when it is non-zero, because a
 * summary built on a trace full of bad fixes should not present itself as precise.
 */
@Composable
fun RideSummaryCard(ride: StoredRide, onDismiss: () -> Unit, onShare: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ride finished", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(ride.name, style = MaterialTheme.typography.bodyLarge)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Distance", formatDistance(ride.distanceMeters))
                Stat("Riding", formatDuration(ride.movingMillis))
                Stat("Total", formatDuration(ride.elapsedMillis))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Average", formatSpeed(ride.averageMovingSpeedMps))
                Stat("Top", formatSpeed(ride.maxSpeedMps))
                Stat("Stops", ride.stopCount.toString())
            }
            Text(
                "Average and top speed are measured while moving, and \"total\" includes the time " +
                    "you were stopped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ride.discardedPoints > 0) {
                Text(
                    "${ride.discardedPoints} GPS fixes were discarded as implausible, so these " +
                        "numbers are approximate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onShare(shareText(ride)) }) { Text("Share") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

/** Finished rides, for the home screen. */
@Composable
fun RideHistoryCard(rides: List<StoredRide>) {
    if (rides.isEmpty()) return
    val stamp = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Past rides", style = MaterialTheme.typography.titleMedium)
            for (ride in rides.take(8)) {
                Column {
                    Text(ride.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${stamp.format(Date(ride.startedAtMillis))} · " +
                            "${formatDistance(ride.distanceMeters)} · " +
                            "${formatDuration(ride.movingMillis)} riding · " +
                            formatSpeed(ride.averageMovingSpeedMps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun shareText(ride: StoredRide): String = buildString {
    append(ride.name)
    append(" — ")
    append(formatDistance(ride.distanceMeters))
    append(" in ")
    append(formatDuration(ride.movingMillis))
    append(" riding")
    ride.averageMovingSpeedMps?.let { append(", averaging ${formatSpeed(it)}") }
    ride.maxSpeedMps?.let { append(", topping ${formatSpeed(it)}") }
    if (ride.riderCount > 1) append(", ${ride.riderCount} riders")
    append(".")
}
