package io.github.meko123456.ridetogether.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.meko123456.ridetogether.android.backend.RiderNameStore
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.capped

/**
 * The one question a shared backend needs answered: what the other riders should call you.
 * Asked once, and nothing else is: no account, no email, no password.
 */
@Composable
fun NameScreen(onSubmit: (String) -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf("") }
    val usable = RiderNameStore.tidy(name).isNotEmpty()
    ConnectionColumn(modifier) {
        Text("What should the group call you?", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.capped(Member.MAX_NAME_LENGTH) },
            label = { Text("Your name") },
            placeholder = { Text("Nino") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (usable) onSubmit(name) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSubmit(name) }, enabled = usable, modifier = Modifier.fillMaxWidth()) {
            Text("Continue")
        }
        Text(
            "It is shown to the riders you ride with, on the map and in the rider list. " +
                "That is all RideTogether asks for: no account, no email.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ConnectingScreen(modifier: Modifier = Modifier) {
    ConnectionColumn(modifier, center = true) {
        CircularProgressIndicator()
        Text("Connecting…", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Only ever the first launch: after that, the app opens as the rider it was, connection or not. */
@Composable
fun UnreachableScreen(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    ConnectionColumn(modifier) {
        Text("RideTogether needs a connection the first time", style = MaterialTheme.typography.titleMedium)
        Text(
            "Opening it for the first time is when your phone is given its place in rides, and " +
                "that needs the internet. After that it opens without one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Try again")
        }
    }
}

@Composable
private fun ConnectionColumn(
    modifier: Modifier,
    center: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Centred only when there is nothing to scroll: inside a scroll the height is unbounded, so
    // there is no middle to centre in.
    val scroll = if (center) Modifier else Modifier.verticalScroll(rememberScrollState())
    Column(
        modifier = modifier
            .fillMaxSize()
            .then(scroll)
            .padding(20.dp),
        verticalArrangement = if (center) Arrangement.spacedBy(16.dp, Alignment.CenterVertically) else Arrangement.spacedBy(16.dp),
        horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text("RideTogether", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        content()
    }
}
