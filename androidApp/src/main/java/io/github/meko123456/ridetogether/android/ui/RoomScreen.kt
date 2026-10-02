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
import io.github.meko123456.ridetogether.alerts.RiderAssessment
import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.android.map.RiderMap
import io.github.meko123456.ridetogether.android.history.StoredRide
import io.github.meko123456.ridetogether.crash.CrashSignal
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.QuickMessage
import io.github.meko123456.ridetogether.model.RideEvent
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
    /** Null where the riders are real: see [RideViewModel.ridesStayOnThisPhone]. */
    onAddDemoRider: (() -> Unit)?,
    onShare: (String) -> Unit,
    onBack: () -> Unit,
    locationLine: String,
    voiceLine: String,
    feed: List<RideEvent>,
    onSendMessage: (QuickMessage) -> Unit,
    /** Null where the riders are real, as for [onAddDemoRider]. */
    onSimulateMessage: ((QuickMessage) -> Unit)?,
    summary: StoredRide?,
    onDismissSummary: () -> Unit,
    positions: Map<String, RiderSample>,
    assessments: List<RiderAssessment>,
    selfId: String,
    crashSignal: CrashSignal?,
    onCancelCrash: () -> Unit,
    onAcknowledgeCrash: () -> Unit,
    onSimulateImpact: () -> Unit,
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

        // Above everything else, including the ride's own status: if the app thinks you may be
        // hurt, nothing else on this screen matters.
        crashSignal?.let { signal ->
            CrashCountdownCard(
                signal = signal,
                onCancel = onCancelCrash,
                onAcknowledge = onAcknowledgeCrash,
            )
        }

        summary?.let { finished ->
            RideSummaryCard(ride = finished, onDismiss = onDismissSummary, onShare = onShare)
        }

        // The map only exists while the ride does, which is the same rule as the location
        // collection behind it: no ride, nothing to draw and nothing being collected.
        if (room.state.sharesLocation) {
            Card {
                RiderMap(
                    positions = positions,
                    assessments = assessments,
                    selfId = selfId,
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                )
            }
        }

        StatusCard(room)

        // What the phone is actually doing, rather than what the room state implies it should be.
        // The two can disagree — permission refused, the provider still warming up — and a
        // privacy claim you cannot verify on screen is worth very little.
        Card {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Your position", style = MaterialTheme.typography.titleMedium)
                Text(locationLine, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    "Riders  ${room.members.size}/${room.maxRiders}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                room.members.forEachIndexed { index, member ->
                    if (index > 0) HorizontalDivider()
                    MemberRow(
                        name = shownName(member, selfId),
                        member = member,
                        onClick = { onToggleSweep(member.riderId) },
                    )
                }
                if (onAddDemoRider != null && !room.isFull) {
                    HorizontalDivider()
                    TextButton(
                        onClick = onAddDemoRider,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text("Add a demo rider")
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

        MessagesCard(
            enabled = room.state.sharesLocation,
            onSend = onSendMessage,
            onSimulate = onSimulateMessage,
            onSimulateImpact = onSimulateImpact,
            voiceLine = voiceLine,
        )

        FeedCard(feed = feed, nameOf = { id -> room.member(id)?.displayName ?: "A rider" })
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

/**
 * A rider's name as this phone shows it. Its own row says so, because on a shared backend that row
 * carries the rider's real name and could be anyone's; on this phone alone it is already "You".
 */
internal fun shownName(member: Member, selfId: String): String =
    if (member.riderId == selfId && !member.displayName.equals("You", ignoreCase = true)) {
        "${member.displayName} (you)"
    } else {
        member.displayName
    }

@Composable
private fun MemberRow(name: String, member: Member, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge)
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

/**
 * The one-tap messages (spec 2.4). Four buttons and no free text on purpose: anything requiring a
 * keyboard cannot be sent from a bike, so the canned set is the feature rather than a limitation.
 */
@Composable
private fun MessagesCard(
    enabled: Boolean,
    onSend: (QuickMessage) -> Unit,
    onSimulate: ((QuickMessage) -> Unit)?,
    onSimulateImpact: () -> Unit,
    voiceLine: String,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Say something", style = MaterialTheme.typography.titleMedium)
            for (message in QuickMessage.entries) {
                OutlinedButton(
                    onClick = { onSend(message) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(message.text)
                }
            }
            HorizontalDivider()
            Text(
                voiceLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onSimulate != null) {
                Text(
                    "Your own messages are never read back to you, so hearing the voice needs a " +
                        "message from someone else, and in this build nobody else is in the ride. " +
                        "To hear one anyway:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onSimulate(QuickMessage.PULL_OVER_NEXT_SAFE_SPOT) },
                    enabled = enabled,
                ) {
                    Text("Hear a message from another rider (demo)")
                }
            }
            TextButton(onClick = onSimulateImpact, enabled = enabled) {
                Text("Simulate an impact (demo)")
            }
        }
    }
}

/** The append-only ride log (spec 2.4), newest first, because that is how it gets read. */
@Composable
private fun FeedCard(feed: List<RideEvent>, nameOf: (String) -> String) {
    if (feed.isEmpty()) return
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ride log", style = MaterialTheme.typography.titleMedium)
            for (event in feed.take(12)) {
                Text(
                    describe(event, nameOf),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Feed copy, which is not the same as spoken copy: this is read at a standstill, so it can carry
 * detail a spoken line has no room for.
 */
private fun describe(event: RideEvent, nameOf: (String) -> String): String {
    val who = nameOf(event.riderId)
    return when (event) {
        is RideEvent.Joined -> "$who joined"
        is RideEvent.Left -> "$who left"
        is RideEvent.StateChanged -> when (event.to) {
            RoomState.RIDING -> "Ride started"
            RoomState.PAUSED -> "Ride paused"
            RoomState.ENDED -> "Ride ended — location sharing stopped"
            RoomState.LOBBY -> "Back in the lobby"
        }
        is RideEvent.FellBehind -> "$who dropped back (${event.gapMeters.toInt()} m)"
        is RideEvent.Rejoined -> "$who is back with the group"
        is RideEvent.Responded -> "$who answered: ${event.response.name.lowercase().replace('_', ' ')}"
        is RideEvent.PossibleIncident -> "$who may have come off"
        is RideEvent.SignalLost -> "$who lost signal"
        is RideEvent.SignalRestored -> "$who is reporting again"
        is RideEvent.Message -> "$who: ${event.message.text}"
        is RideEvent.BatterySaver -> "$who is low on battery (${event.batteryPercent}%)"
    }
}

private fun label(state: RoomState): String = when (state) {
    RoomState.LOBBY -> "Lobby"
    RoomState.RIDING -> "Riding"
    RoomState.PAUSED -> "Paused"
    RoomState.ENDED -> "Ended"
}
