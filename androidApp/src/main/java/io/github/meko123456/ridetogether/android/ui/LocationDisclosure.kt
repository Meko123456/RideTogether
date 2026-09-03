package io.github.meko123456.ridetogether.android.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The prominent disclosure Google Play requires *before* the system permission dialog.
 *
 * The rules it has to satisfy, from `docs/PLAY_LOCATION_COMPLIANCE.md`: it names the app, says
 * what is collected and why, appears before the runtime request, and offers a refusal that is not
 * a dead end. The system dialog does not count as disclosure, and a "Continue" that also accepts
 * something else does not count as consent — so this screen does exactly one thing.
 *
 * The wording is the wording from that document, kept identical on purpose: what the store listing
 * claims, what the privacy policy says and what the app actually shows a rider all have to match,
 * and the cheapest way to keep three things in step is to write them once.
 */
@Composable
fun LocationDisclosure(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RideTogether needs your location while you ride") },
        text = {
            Text(
                "While a ride is running, RideTogether shares your position with the other riders " +
                    "in that ride so the group can see each other on the map and be warned if " +
                    "someone drops back.\n\n" +
                    "Your location is collected only between tapping Start ride and the ride " +
                    "ending, and only while the ride notification is showing. It is never " +
                    "collected when no ride is running, and it is never used for advertising.\n\n" +
                    "You can stop sharing at any time by ending the ride or leaving the room.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
