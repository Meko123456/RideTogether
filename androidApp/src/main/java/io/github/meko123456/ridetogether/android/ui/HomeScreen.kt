package io.github.meko123456.ridetogether.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.meko123456.ridetogether.model.JoinCode

/** Start a ride or join one. The two things a rider does in a car park with gloves half on. */
@Composable
fun HomeScreen(
    rideName: String,
    codeInput: String,
    resolvedCode: JoinCode?,
    onRideNameChange: (String) -> Unit,
    onCodeInputChange: (String) -> Unit,
    onCreateRide: () -> Unit,
    onJoinRide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "RideTogether",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Keep the group together. Nobody gets left behind at a junction.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Start a ride", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = rideName,
                    onValueChange = onRideNameChange,
                    label = { Text("Name it (optional)") },
                    placeholder = { Text("Gudauri and back") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onCreateRide, modifier = Modifier.fillMaxWidth()) {
                    Text("Create ride")
                }
                Text(
                    "You lead. Everyone else joins with the ${JoinCode.LENGTH}-character code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Join a ride", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = onCodeInputChange,
                    label = { Text("Ride code") },
                    placeholder = { Text("A2B4C7") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Tell the rider what their sloppy typing became, but only when it changed —
                // silently "fixing" a code is how you end up in the wrong ride.
                val normalised = JoinCode.normalise(codeInput)
                if (normalised.isNotEmpty() && normalised != codeInput.trim().uppercase()) {
                    Text(
                        "Reads as $normalised",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedButton(
                    onClick = onJoinRide,
                    enabled = resolvedCode != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Join")
                }
                Text(
                    "O reads as 0, I and L read as 1 — say it out loud through a helmet and it still works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            "Your location is shared with the group only while a ride is running, and stops the " +
                "moment it ends.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}
