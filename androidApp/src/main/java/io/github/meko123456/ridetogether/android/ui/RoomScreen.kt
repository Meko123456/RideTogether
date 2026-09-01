package io.github.meko123456.ridetogether.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.Role
import io.github.meko123456.ridetogether.model.Room
import io.github.meko123456.ridetogether.model.RoomState
import io.github.meko123456.ridetogether.room.JoinPolicy
import io.github.meko123456.ridetogether.room.RoomCommand
import io.github.meko123456.ridetogether.room.RoomStateMachine

/**
 * The ride room: the code to read out, who's in, and the lifecycle controls.
 *
 * Button availability comes from [RoomStateMachine.canTransition] rather than from a hand-written
 * list of cases here, so the UI cannot drift from the rules the tests pin down.
 */
@Composable
fun RoomScreen(
    room: Room,
    onCommand: (RoomCommand) -> Unit,
    onToggleSweep: (String) -> Unit,
    onAddDemoRider: () -> Unit,
    onShare: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.height(0.dp))
        }

        Text(room.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Ride code", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = room.code.value,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 10.sp,
                    ),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { onShare(JoinPolicy.deepLink(room)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Share invite link")
                }
            }
        }

        StatusCard(room)

        Card {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    "Riders  ${room.members.size}/${room.maxRiders}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                room.members.forEachIndexed { index, member ->
                    if (index > 0) HorizontalDivider()
                    MemberRow(member = member, onClick = { onToggleSweep(member.riderId) })
                }
                if (!room.isFull) {
                    HorizontalDivider()
                    TextButton(
                        onClick = onAddDemoRider,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text("Add a rider (demo — until the network layer lands)")
                    }
                }
                Text(
                    "Tap a rider to make them the sweep. The sweep rides last on purpose, so the " +
                        "app never nags them for being at the back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        Controls(room = room, onCommand = onCommand)
        Spacer(Modifier.height(16.dp))
    }
}

/** What the current state means in practice — the privacy promise, stated plainly. */
@Composable
private fun StatusCard(room: Room) {
    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                AssistChip(onClick = {}, label = { Text(label(room.state)) })
            }
            Text(
                if (room.state.sharesLocation) "Sharing your location with the group"
                else "Not sharing your location",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                when {
                    room.state.separationAlertsActive -> "Watching for anyone falling behind"
                    // Worth saying out loud: pausing quietens the nagging, not the safety net.
                    room.state.safetyAlertsActive ->
                        "Fall-behind alerts off while paused — still watching for trouble"
                    else -> "Alerts off"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MemberRow(member: Member, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(member.displayName, style = MaterialTheme.typography.bodyLarge)
            val duty = buildList {
                when (member.role) {
                    Role.LEADER -> add("Leader")
                    Role.CO_LEADER -> add("Co-leader")
                    Role.RIDER -> Unit
                }
                if (member.isSweep) add("Sweep")
            }
            if (duty.isNotEmpty()) {
                Text(
                    duty.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun Controls(room: Room, onCommand: (RoomCommand) -> Unit) {
    val canStart = RoomStateMachine.canTransition(room.state, RoomState.RIDING)
    val canPause = RoomStateMachine.canTransition(room.state, RoomState.PAUSED)
    val canEnd = RoomStateMachine.canTransition(room.state, RoomState.ENDED)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canStart) {
            Button(
                onClick = {
                    onCommand(if (room.state == RoomState.PAUSED) RoomCommand.ResumeRide else RoomCommand.StartRide)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (room.state == RoomState.PAUSED) "Resume ride" else "Start ride")
            }
        }
        if (canPause) {
            OutlinedButton(onClick = { onCommand(RoomCommand.PauseRide) }, modifier = Modifier.fillMaxWidth()) {
                Text("Pause — fuel stop")
            }
        }
        if (canEnd) {
            OutlinedButton(onClick = { onCommand(RoomCommand.EndRide) }, modifier = Modifier.fillMaxWidth()) {
                Text("End ride")
            }
        }
        if (room.state == RoomState.ENDED) {
            Text(
                "This ride is finished. Nothing is being shared.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun label(state: RoomState): String = when (state) {
    RoomState.LOBBY -> "Lobby"
    RoomState.RIDING -> "Riding"
    RoomState.PAUSED -> "Paused"
    RoomState.ENDED -> "Ended"
}
