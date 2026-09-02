package io.github.meko123456.ridetogether.announce

import io.github.meko123456.ridetogether.alerts.Alert
import io.github.meko123456.ridetogether.model.QuickMessage
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.model.RoomState
import kotlinx.datetime.Instant

/**
 * Decides what a rider actually hears.
 *
 * The spec's requirement is that every critical alert is spoken because the screen is never looked
 * at while moving (§2.4). The hard part is not the speaking — it is the restraint. A helmet is one
 * channel, a rider can absorb about one sentence, and an app that narrates every join, every
 * signal blip and every rider's private prompt trains its user to stop listening. Then the one
 * announcement that mattered arrives and is ignored, which is the same failure the alert engine is
 * built to avoid, moved into the audio layer.
 *
 * So the rules are about who hears what, and how rarely:
 *
 * - **A rider's fall-behind prompt is spoken to that rider only.** It is a cheap question — "you
 *   have dropped back, all good?" — and broadcasting it to five other riders turns it into an
 *   accusation and four interruptions. This is the engine's cheap-question/expensive-alarm split
 *   carried into speech.
 * - **Never announce the resolution of something that was never announced.** "Alex is back with
 *   the group" is meaningless to riders who were never told he had gone.
 * - **At most one non-critical line per tick.** The rest is on the screen and in the feed, where
 *   it can be read at the next stop. Criticals ignore this, because they are rare by construction.
 * - **Nothing about joining, leaving, or answering a prompt is ever spoken.** Those are feed
 *   entries. A ride does not need narrating.
 */
class Announcer(
    private val selfId: String,
    private val config: AnnounceConfig = AnnounceConfig(),
) {

    private var lastSpokeAt: Instant? = null
    private val lastByKey = mutableMapOf<String, Instant>()

    /** Riders the group has been told something about, so a resolution makes sense. */
    private val outstanding = mutableSetOf<String>()

    /**
     * @param nameOf resolves a rider id to the name to speak. Speech needs the name, and the
     *   announcer has no business knowing about the room's member list.
     */
    fun announce(
        now: Instant,
        alerts: List<Alert> = emptyList(),
        events: List<RideEvent> = emptyList(),
        nameOf: (String) -> String,
    ): List<Announcement> {
        val candidates = buildList {
            for (alert in alerts) lineFor(alert, nameOf)?.let(::add)
            for (event in events) lineFor(event, nameOf)?.let(::add)
        }

        val spoken = mutableListOf<Announcement>()

        // Criticals first, and each one on its own terms: they are not held back by the quiet
        // period, and their only limit is not repeating the identical line too soon.
        for (candidate in candidates.filter { it.priority == Priority.CRITICAL }) {
            if (isRepeat(candidate, now, config.criticalRepeatWindow)) continue
            spoken += candidate
            lastByKey[candidate.key] = now
        }

        // Then at most one of everything else, highest priority and earliest first.
        val rest = candidates
            .filter { it.priority != Priority.CRITICAL }
            .sortedWith(compareBy({ it.priority.ordinal }, { it.at }))
        for (candidate in rest) {
            if (isRepeat(candidate, now, config.repeatWindow)) continue
            // One rule doing both jobs: stop if a critical already took the channel, and stop
            // after speaking one line of our own. (There used to be a trailing `break` saying the
            // second half again; mutation testing showed it was unreachable.)
            if (spoken.isNotEmpty()) break
            if (!channelIsFree(now)) break
            spoken += candidate
            lastByKey[candidate.key] = now
        }

        if (spoken.isNotEmpty()) lastSpokeAt = now
        return spoken
    }

    /** Forget everything — a new ride starts with a clear channel. */
    fun reset() {
        lastSpokeAt = null
        lastByKey.clear()
        outstanding.clear()
    }

    private fun isRepeat(candidate: Announcement, now: Instant, window: kotlin.time.Duration): Boolean {
        val last = lastByKey[candidate.key] ?: return false
        return now - last < window
    }

    private fun channelIsFree(now: Instant): Boolean {
        val last = lastSpokeAt ?: return true
        return now - last >= config.quietAfterSpeaking
    }

    private fun lineFor(alert: Alert, nameOf: (String) -> String): Announcement? {
        val name = nameOf(alert.riderId)
        return when (alert) {
            is Alert.FallingBehind -> {
                // Only the rider who dropped back hears this. Everyone else sees it on screen.
                if (alert.riderId != selfId) return null
                Announcement(
                    text = "You have dropped back. Are you all right?",
                    priority = Priority.IMPORTANT,
                    at = alert.at,
                    key = "behind:${alert.riderId}",
                )
            }

            is Alert.PossibleIncident -> {
                outstanding += alert.riderId
                Announcement(
                    text = if (alert.riderId == selfId) {
                        "The group has been alerted that you may have come off."
                    } else {
                        "$name may have come off. Stopping is recommended."
                    },
                    priority = Priority.CRITICAL,
                    at = alert.at,
                    key = "incident:${alert.riderId}",
                )
            }

            is Alert.SignalLost -> {
                if (alert.riderId == selfId) return null // your own phone told you nothing new
                outstanding += alert.riderId
                Announcement(
                    text = "$name has lost signal. Their position is not being updated.",
                    priority = Priority.IMPORTANT,
                    at = alert.at,
                    key = "quiet:${alert.riderId}",
                )
            }

            is Alert.SignalRestored -> resolutionFor(
                riderId = alert.riderId,
                at = alert.at,
                text = "$name is reporting again.",
                key = "back:${alert.riderId}",
            )

            is Alert.Rejoined -> resolutionFor(
                riderId = alert.riderId,
                at = alert.at,
                text = if (alert.riderId == selfId) "You are back with the group." else "$name is back with the group.",
                key = "rejoined:${alert.riderId}",
            )
        }
    }

    /**
     * A resolution is only worth speaking if the problem was. Otherwise the group hears that
     * someone is "back" without ever having been told they were gone.
     */
    private fun resolutionFor(riderId: String, at: Instant, text: String, key: String): Announcement? {
        if (riderId !in outstanding) return null
        outstanding -= riderId
        return Announcement(text, Priority.ROUTINE, at, key)
    }

    private fun lineFor(event: RideEvent, nameOf: (String) -> String): Announcement? = when (event) {
        // The feed's job, not the headset's. A ride does not need narrating.
        is RideEvent.Joined,
        is RideEvent.Left,
        is RideEvent.Responded,
        is RideEvent.FellBehind,
        is RideEvent.Rejoined,
        is RideEvent.PossibleIncident,
        is RideEvent.SignalLost,
        is RideEvent.SignalRestored,
        -> null

        is RideEvent.BatterySaver ->
            if (event.riderId == selfId) {
                null
            } else {
                Announcement(
                    text = "${nameOf(event.riderId)} is low on battery and reporting less often.",
                    priority = Priority.ROUTINE,
                    at = event.at,
                    key = "battery:${event.riderId}",
                )
            }

        is RideEvent.Message -> {
            // The whole point of a one-tap message is that it is heard, so it is spoken — but
            // never back to the person who sent it.
            if (event.riderId == selfId) {
                null
            } else {
                Announcement(
                    text = "${nameOf(event.riderId)}: ${spoken(event.message)}",
                    priority = Priority.IMPORTANT,
                    at = event.at,
                    key = "message:${event.riderId}:${event.message.name}",
                )
            }
        }

        is RideEvent.StateChanged -> when (event.to) {
            // Worth hearing: you may be a kilometre back and unaware the group has stopped.
            RoomState.PAUSED -> Announcement(
                "The ride is paused.", Priority.IMPORTANT, event.at, "state:paused",
            )
            RoomState.RIDING -> Announcement(
                "The ride is under way.", Priority.ROUTINE, event.at, "state:riding",
            )
            RoomState.ENDED -> Announcement(
                "The ride has ended. Location sharing has stopped.",
                Priority.IMPORTANT, event.at, "state:ended",
            )
            RoomState.LOBBY -> null
        }
    }

    /**
     * Quick messages are written for a screen; spoken, they need to survive wind noise and a
     * helmet, so a couple of them are rephrased rather than read out verbatim.
     */
    private fun spoken(message: QuickMessage): String = when (message) {
        QuickMessage.FUEL_STOP_NEEDED -> "needs a fuel stop"
        QuickMessage.SLOW_DOWN -> "asks the group to slow down"
        QuickMessage.PULL_OVER_NEXT_SAFE_SPOT -> "asks the group to pull over at the next safe spot"
        QuickMessage.ALL_GOOD -> "says all good"
    }
}
