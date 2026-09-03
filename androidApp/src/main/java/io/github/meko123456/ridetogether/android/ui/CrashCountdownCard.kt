package io.github.meko123456.ridetogether.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.meko123456.ridetogether.crash.CrashSignal
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock

/**
 * The cancel button that makes crash detection acceptable at all.
 *
 * The detector is *allowed* to be wrong, because being wrong costs one tap — that is the trade the
 * whole feature rests on, so this has to be the loudest thing on the screen and the easiest thing
 * to dismiss. One button, no confirmation, no menu.
 *
 * The countdown is drawn from the expiry the detector published rather than counted locally, so a
 * recomposition, a rotation or a trip through the background cannot restart it.
 */
@Composable
fun CrashCountdownCard(
    signal: CrashSignal,
    onCancel: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    when (signal) {
        is CrashSignal.CountdownStarted -> {
            var remaining by remember(signal.expiresAt) { mutableIntStateOf(secondsLeft(signal)) }
            LaunchedEffect(signal.expiresAt) {
                while (remaining > 0) {
                    delay(250)
                    remaining = secondsLeft(signal)
                }
            }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Are you all right?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "It looks like you may have come off. The group will be alerted in " +
                            "$remaining seconds unless you say otherwise.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("I'm fine")
                    }
                }
            }
        }

        is CrashSignal.CrashConfirmed -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The group has been alerted",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    signal.location?.let {
                        "Your position was sent: %.5f, %.5f".format(it.latitude, it.longitude)
                    } ?: "No position was available to send.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onAcknowledge) { Text("Understood") }
            }
        }

        is CrashSignal.CountdownCancelled -> Unit // nothing to say; the card simply goes away
    }
}

private fun secondsLeft(signal: CrashSignal.CountdownStarted): Int {
    val remaining = signal.expiresAt - Clock.System.now()
    return remaining.inWholeSeconds.coerceAtLeast(0).toInt()
}
