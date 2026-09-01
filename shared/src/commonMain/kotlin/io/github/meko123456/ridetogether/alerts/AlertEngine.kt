package io.github.meko123456.ridetogether.alerts

import io.github.meko123456.ridetogether.model.FallbackResponse
import io.github.meko123456.ridetogether.model.Geo
import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.RiderStatus
import io.github.meko123456.ridetogether.model.RoomState
import kotlinx.datetime.Instant
import kotlin.time.Duration

/** One rider's reported position at a moment in time — the engine's only input about the world. */
data class RiderSample(
    val riderId: String,
    val location: LatLng,
    val speedMps: Float?,
    val at: Instant,
    /** How often this rider is currently reporting, so staleness can scale with it (spec 3.4). */
    val reportingInterval: Duration? = null,
)

/** Everything the engine needs to judge one tick. */
data class EngineTick(
    val now: Instant,
    val roomState: RoomState,
    val members: List<Member>,
    /** Latest sample per rider. A rider with no entry has never reported. */
    val samples: Map<String, RiderSample>,
    val route: List<LatLng>? = null,
)

/** What the engine believes about one rider right now. */
data class RiderAssessment(
    val riderId: String,
    val status: RiderStatus,
    val gapMeters: Double?,
    val isStale: Boolean,
    val awaitingResponse: Boolean,
)

/** The result of a tick: the alerts to announce, plus the full picture for the UI. */
data class EngineResult(
    val alerts: List<Alert>,
    val assessments: List<RiderAssessment>,
)

/**
 * Fall-behind and incident detection — the part of RideTogether that has to be right.
 *
 * ## Why not just distance
 *
 * The spec's own rule is "gap > 1.5 km AND the gap has been increasing for 60 s". That is not
 * sufficient, and the failure is ordinary city riding: at 60 km/h a group opens **a kilometre a
 * minute**, so a rider held at a long traffic light crosses 1.5 km with a monotonically
 * increasing gap *inside* the grace window. Implemented literally, the app would cry wolf at
 * every red light — and a safety alert nobody believes is worse than no alert.
 *
 * The honest position is that **a long red light and a breakdown are indistinguishable from a
 * position trace**: both leave a stationary rider while the group pulls away. A 100-second light
 * at 60 km/h opens 1.7 km. So the engine does not pretend to tell them apart — it makes the
 * cheap question cheap and the loud alarm expensive:
 *
 *  1. [Alert.FallingBehind] only prompts **that rider**, on their own phone, with one tap. A
 *     light occasionally causing it is a minor annoyance, not a false alarm to the group.
 *  2. [Alert.PossibleIncident] — the loud, everyone-hears-it one — additionally requires the
 *     rider to have been **continuously stationary longer than any plausible light cycle**
 *     ([AlertConfig.minStationaryForIncident], two minutes by default) *and* to have ignored the
 *     prompt. A light cannot hold you that long; a ditch can.
 *  3. **Any movement cancels the pending prompt.** This is what makes the light case heal itself:
 *     the moment they pull away from the line, the question is withdrawn and nothing escalates.
 *  4. **The growth must be unbroken.** The timer resets the instant a gap stops growing, so
 *     "rising for 60 s" means right up to this moment — a rider who levels off or starts closing
 *     begins the grace period again. Plus **hysteresis** (clear at 75 % of the threshold) so an
 *     alert cannot flap either side of 1.5 km.
 *  5. **Stationary is measured over a window**, never one sample, and **the sweep is exempt**
 *     from separation alerts because being last is that rider's job.
 *
 * ## Time is an input
 *
 * The engine is driven by [EngineTick], not only by location events, because the two states that
 * matter most — *no data for 45 s* and *no answer for 90 s* — produce **no event at all**. An
 * engine that only reacts to incoming samples can never notice the rider who went quiet. The
 * clock arrives as a parameter, so the whole thing is deterministic and testable against
 * synthetic traces with zero platform dependencies.
 */
class AlertEngine(private val config: AlertConfig = AlertConfig()) {

    /** Per-rider memory between ticks. */
    private data class Track(
        val gapMeters: Double? = null,
        val gapRisingSince: Instant? = null,
        val alerted: Boolean = false,
        val promptedAt: Instant? = null,
        val responded: FallbackResponse? = null,
        val movingSince: Instant? = null,
        val stationarySince: Instant? = null,
        val incident: Boolean = false,
        val signalLost: Boolean = false,
        val firstSeenAt: Instant? = null,
        val lastSampleAt: Instant? = null,
    )

    private val tracks = mutableMapOf<String, Track>()

    /** A rider answered the "all good?" prompt; clears the escalation timer. */
    fun onResponse(riderId: String, response: FallbackResponse) {
        val track = tracks[riderId] ?: Track()
        tracks[riderId] = track.copy(responded = response, promptedAt = null)
    }

    /** Forget a rider entirely (they left the room). */
    fun forget(riderId: String) {
        tracks.remove(riderId)
    }

    fun assess(tick: EngineTick): EngineResult {
        val alerts = mutableListOf<Alert>()
        val assessments = mutableListOf<RiderAssessment>()
        val progress = routeProgress(tick)
        val gaps = gapsFor(tick, progress)

        for (member in tick.members) {
            val id = member.riderId
            val sample = tick.samples[id]
            var track = tracks[id] ?: Track()
            if (sample != null && (track.firstSeenAt == null)) track = track.copy(firstSeenAt = sample.at)
            if (sample != null) track = track.copy(lastSampleAt = sample.at)

            // ---- freshness -------------------------------------------------------------
            val lastSeen = sample?.at ?: track.lastSampleAt
            val stale = lastSeen == null || (tick.now - lastSeen) > staleWindow(sample)
            if (stale && !track.signalLost && lastSeen != null) {
                alerts += Alert.SignalLost(id, tick.now, lastSeen)
                track = track.copy(signalLost = true)
            } else if (!stale && track.signalLost) {
                alerts += Alert.SignalRestored(id, tick.now)
                track = track.copy(signalLost = false)
            }

            // ---- motion ----------------------------------------------------------------
            val speed = sample?.speedMps?.toDouble()
            if (speed != null) {
                track = if (speed <= config.stationarySpeedMps) {
                    track.copy(stationarySince = track.stationarySince ?: sample.at, movingSince = null)
                } else {
                    track.copy(movingSince = track.movingSince ?: sample.at, stationarySince = null)
                }
            }
            val stationaryFor = track.stationarySince?.let { tick.now - it }
            val convincinglyStationary = stationaryFor != null && stationaryFor >= config.stationaryWindow
            val movingAgain = speed != null && speed > config.stationarySpeedMps

            // ---- separation ------------------------------------------------------------
            val gap = gaps[id]
            val previousGap = track.gapMeters
            val rising = gap != null && previousGap != null && gap > previousGap
            track = when {
                gap == null -> track.copy(gapRisingSince = null)
                rising -> track.copy(gapRisingSince = track.gapRisingSince ?: tick.now)
                else -> track.copy(gapRisingSince = null)
            }

            // Movement alone must NOT withdraw the question — a rider can be moving and still
            // steadily losing ground, which is the genuine fallback case. It is withdrawn only
            // once they are moving *and* no longer falling further behind, which is precisely
            // what pulling away from a traffic light looks like.
            if (track.promptedAt != null && movingAgain && !rising) {
                track = track.copy(promptedAt = null)
            }

            val separationAllowed = tick.roomState.alertsActive &&
                !member.isSweep &&
                !stale &&
                hasSettledIn(track, tick.now)

            if (separationAllowed && gap != null) {
                val risingLongEnough = track.gapRisingSince?.let { tick.now - it >= config.gracePeriod } == true
                // No separate "still rising" check is needed: the moment a gap stops growing,
                // gapRisingSince is reset above, so risingLongEnough already means "has been
                // growing continuously, right up to now". A rider who levels off or starts
                // closing has to begin the whole grace period again.
                if (!track.alerted && gap > config.gapThresholdMeters && risingLongEnough) {
                    alerts += Alert.FallingBehind(id, tick.now, gap)
                    track = track.copy(alerted = true, promptedAt = tick.now, responded = null)
                } else if (track.alerted && gap < config.clearThresholdMeters) {
                    // Hysteresis: only a real recovery clears it, not a wobble around 1.5 km.
                    alerts += Alert.Rejoined(id, tick.now)
                    track = track.copy(alerted = false, promptedAt = null, responded = null, incident = false)
                }
            }

            // ---- escalation ------------------------------------------------------------
            val unanswered = track.promptedAt?.let { tick.now - it >= config.responseTimeout } == true
            if (!track.incident && unanswered && track.responded == null) {
                // Stale data during the escalation window is signal loss, NOT an incident: we
                // genuinely do not know, and saying "possible crash" on no evidence is how the
                // alert loses its meaning.
                val stationaryLongEnough = stationaryFor != null && stationaryFor >= config.minStationaryForIncident
                // A *current* fix is required, not merely data that has not aged into "stale"
                // yet. Between a rider's last packet and the staleness deadline there is a
                // window where motion state is remembered but nothing new has arrived; escalating
                // there produced a "possible incident" carrying a null location — an alarm with
                // no pin to ride to, raised on no evidence. If there is no fix this tick, we do
                // not know what happened, and the honest output is silence until SIGNAL_LOST.
                if (sample != null && !stale && stationaryLongEnough) {
                    alerts += Alert.PossibleIncident(id, tick.now, sample?.location, lastSeen ?: tick.now)
                    track = track.copy(incident = true, promptedAt = null)
                } else if (stale) {
                    track = track.copy(promptedAt = null)
                }
            }

            tracks[id] = track.copy(gapMeters = gap ?: track.gapMeters)
            assessments += RiderAssessment(
                riderId = id,
                status = statusOf(track, stale, convincinglyStationary, tick.roomState),
                gapMeters = gap,
                isStale = stale,
                awaitingResponse = track.promptedAt != null,
            )
        }
        return EngineResult(alerts, assessments)
    }

    /**
     * A rider's status, flattened for display. An incident is **sticky**: once raised it is not
     * quietly downgraded to grey "signal lost" when the phone stops reporting — which is exactly
     * what a crashed phone does.
     */
    private fun statusOf(
        track: Track,
        stale: Boolean,
        stationary: Boolean,
        roomState: RoomState,
    ): RiderStatus = when {
        track.incident -> RiderStatus.POSSIBLE_INCIDENT
        stale -> RiderStatus.SIGNAL_LOST
        track.alerted && roomState.alertsActive -> RiderStatus.FALLING_BEHIND
        stationary -> RiderStatus.STOPPED
        else -> RiderStatus.ACTIVE
    }

    /** Staleness scales with how often the rider is actually reporting (see [AlertConfig]). */
    private fun staleWindow(sample: RiderSample?): Duration {
        val interval = sample?.reportingInterval ?: return config.staleAfter
        val scaled = interval * config.staleIntervalMultiplier
        return if (scaled > config.staleAfter) scaled else config.staleAfter
    }

    /** A rider who has only just joined has no gap history worth judging. */
    private fun hasSettledIn(track: Track, now: Instant): Boolean =
        track.firstSeenAt?.let { now - it >= config.joinGrace } == true

    /** Along-route progress per rider, when a route exists and the rider is actually on it. */
    private fun routeProgress(tick: EngineTick): Map<String, Double>? {
        val route = tick.route?.takeIf { it.size >= 2 } ?: return null
        val out = mutableMapOf<String, Double>()
        for ((id, sample) in tick.samples) {
            val position = RouteProjection.project(sample.location, route) ?: continue
            // A rider on the other carriageway, or who took a different turn, is not "behind" —
            // measuring them along this route would be meaningless.
            if (position.offRouteMeters <= config.offRouteToleranceMeters) out[id] = position.progressMeters
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * Each rider's gap to the rider immediately ahead.
     *
     * With a route, "ahead" is unambiguous: sort by progress along it. Without one, fall back to
     * straight-line distance to the leader, which is the spec's fallback and is honest about
     * being an approximation.
     */
    private fun gapsFor(tick: EngineTick, progress: Map<String, Double>?): Map<String, Double> {
        if (progress != null && progress.size >= 2) {
            val ordered = progress.entries.sortedByDescending { it.value }
            val gaps = mutableMapOf<String, Double>()
            for (i in 1 until ordered.size) {
                gaps[ordered[i].key] = ordered[i - 1].value - ordered[i].value
            }
            return gaps
        }
        val leaderId = tick.members.firstOrNull { it.role.canControlRoom }?.riderId
        val leader = leaderId?.let { tick.samples[it] } ?: return emptyMap()
        return tick.samples
            .filterKeys { it != leader.riderId }
            .mapValues { (_, sample) -> Geo.distanceMeters(sample.location, leader.location) }
    }
}
