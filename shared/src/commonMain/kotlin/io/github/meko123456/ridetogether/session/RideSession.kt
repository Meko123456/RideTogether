package io.github.meko123456.ridetogether.session

import io.github.meko123456.ridetogether.alerts.Alert
import io.github.meko123456.ridetogether.alerts.AlertConfig
import io.github.meko123456.ridetogether.alerts.AlertEngine
import io.github.meko123456.ridetogether.alerts.EngineTick
import io.github.meko123456.ridetogether.alerts.RiderAssessment
import io.github.meko123456.ridetogether.alerts.RiderSample
import io.github.meko123456.ridetogether.announce.AnnounceConfig
import io.github.meko123456.ridetogether.announce.Announcement
import io.github.meko123456.ridetogether.announce.Announcer
import io.github.meko123456.ridetogether.location.LocationConditions
import io.github.meko123456.ridetogether.location.LocationPolicy
import io.github.meko123456.ridetogether.model.FallbackResponse
import io.github.meko123456.ridetogether.model.LatLng
import io.github.meko123456.ridetogether.model.Member
import io.github.meko123456.ridetogether.model.RideEvent
import io.github.meko123456.ridetogether.model.RoomState
import kotlinx.datetime.Instant
import kotlin.time.Duration

/** Everything the platform layer knows at one moment. */
data class SessionTick(
    val now: Instant,
    val roomState: RoomState,
    val members: List<Member>,
    val samples: Map<String, RiderSample>,
    val route: List<LatLng>? = null,
    /** This phone's battery, 0..100, or null when it could not be read. */
    val batteryPercent: Int? = null,
    /** Room events since the last tick — quick messages, state changes, battery-saver notices. */
    val events: List<RideEvent> = emptyList(),
)

/** What the platform layer should do about it. */
data class SessionResult(
    /** Per-rider status for the map and the rider list. */
    val assessments: List<RiderAssessment>,
    /** Alerts raised this tick, for the event feed and notifications. */
    val alerts: List<Alert>,
    /** Lines to speak, already filtered for this rider — see [Announcer]. */
    val announcements: List<Announcement>,
    /**
     * How often this phone should be asking for a position, or **null when it must stop** — the
     * kill switch, passed straight through from [LocationPolicy].
     */
    val reportingInterval: Duration?,
    /**
     * True when an announcement is waiting for the audio channel to free. The caller must tick
     * again shortly — see [Announcer.hasPending]; nothing here runs on a clock.
     */
    val pendingAnnouncement: Boolean = false,
)

/**
 * Holds the three pieces of ride logic together so the platform layer does not have to.
 *
 * The alert engine, the announcer and the location policy were each written and tested on their
 * own, which is right — but something has to own the order they run in and the state they share,
 * and if that something is an Android service then the composition itself is the one part with no
 * tests. It is here instead, and it is still pure: no Android, no Firebase, and the clock arrives
 * as a parameter.
 *
 * Two details the composition has to get right, and neither is obvious from the pieces alone:
 *
 * - **The engine's own alerts are announced; the room's echo of them is not.** Every alert also
 *   becomes a `RideEvent` in the feed, so passing both to the announcer would say everything
 *   twice. Only feed events the engine cannot produce — quick messages, state changes, battery
 *   notices — are forwarded.
 * - **The reporting interval follows *this* rider's speed**, not the group's. A rider stopped at
 *   the back of a moving group should be reporting on the stopped interval, and taking the speed
 *   from anyone else would keep their phone awake for no reason.
 */
class RideSession(
    private val selfId: String,
    alertConfig: AlertConfig = AlertConfig(),
    announceConfig: AnnounceConfig = AnnounceConfig(),
) {

    private val engine = AlertEngine(alertConfig)
    private val announcer = Announcer(selfId = selfId, config = announceConfig)

    fun tick(tick: SessionTick, nameOf: (String) -> String): SessionResult {
        val engineResult = engine.assess(
            EngineTick(
                now = tick.now,
                roomState = tick.roomState,
                members = tick.members,
                samples = tick.samples,
                route = tick.route,
            ),
        )

        val announcements = announcer.announce(
            now = tick.now,
            alerts = engineResult.alerts,
            // Deliberately not every event: the feed records an event for each alert too, and
            // forwarding both would announce everything twice.
            events = tick.events.filter(::isAnnounceable),
            nameOf = nameOf,
        )

        return SessionResult(
            assessments = engineResult.assessments,
            alerts = engineResult.alerts,
            announcements = announcements,
            pendingAnnouncement = announcer.hasPending,
            reportingInterval = LocationPolicy.intervalFor(
                LocationConditions(
                    roomState = tick.roomState,
                    speedMps = tick.samples[selfId]?.speedMps?.toDouble(),
                    batteryPercent = tick.batteryPercent,
                ),
            ),
        )
    }

    /** A rider answered their prompt. Passed through so the escalation timer stops. */
    fun onResponse(riderId: String, response: FallbackResponse) {
        engine.onResponse(riderId, response)
    }

    /** A rider left the room: forget them rather than keep judging an absence. */
    fun onRiderLeft(riderId: String) {
        engine.forget(riderId)
    }

    /** A new ride starts with nothing remembered and a clear audio channel. */
    fun reset() {
        announcer.reset()
    }

    /**
     * Feed events the alert engine cannot itself produce. Anything the engine emits as an [Alert]
     * is excluded, because the room records both and the announcer would otherwise say it twice.
     */
    private fun isAnnounceable(event: RideEvent): Boolean = when (event) {
        is RideEvent.Message,
        is RideEvent.StateChanged,
        is RideEvent.BatterySaver,
        -> true

        is RideEvent.FellBehind,
        is RideEvent.Rejoined,
        is RideEvent.PossibleIncident,
        is RideEvent.SignalLost,
        is RideEvent.SignalRestored,
        is RideEvent.Joined,
        is RideEvent.Left,
        is RideEvent.Responded,
        -> false
    }
}
