import Foundation
import Shared

/// Drives the shared Kotlin `RideSession` from SwiftUI.
///
/// Everything that decides anything — who has fallen behind, when that is worth saying out loud,
/// what a gap in metres means — lives in the `shared` module and is already tested there. This type
/// owns no rules. It keeps the rider positions, advances a clock, hands both to `RideSession.tick`,
/// and republishes whatever comes back so the views can draw it.
///
/// Positions are moved by the controls on screen rather than by `CoreLocation`. That is deliberate
/// for this first version: the point is to prove the shared core drives a native iOS UI, and a
/// simulated ride exercises the alert engine far more thoroughly than a phone sitting on a desk
/// ever would. Real location and a map are the next phase, and are what issue #15 sequences after
/// Android beta feedback.
@MainActor
final class RideStore: ObservableObject {

    /// Who this phone is. The announcer says different things about you than about everybody else.
    private let selfId = "rider-1"

    private let roomId = "sunday-run"

    private lazy var session = RideSession(
        selfId: selfId,
        alertConfig: AlertConfig.companion.Default,
        announceConfig: AnnounceConfig.companion.Default
    )

    /// Turns the recorded traces into the ride summary. Also shared, also already tested there:
    /// every judgement call in a summary — that the average excludes stops, that a fix implying
    /// 270 km/h is a glitch and not a rider, that nothing can be claimed across a two-minute hole
    /// in coverage — is `RideSummariser`'s, and none of it is restated here.
    private let summariser = RideSummariser(config: SummaryConfig.companion.Default)

    private let members: [Member] = [
        Member(riderId: "rider-1", displayName: "You", role: Role.leader, isSweep: false, colorArgb: nil, motorcycle: nil),
        Member(riderId: "rider-2", displayName: "Dato", role: Role.rider, isSweep: false, colorArgb: nil, motorcycle: nil),
        Member(riderId: "rider-3", displayName: "Nino", role: Role.coLeader, isSweep: false, colorArgb: nil, motorcycle: nil),
        Member(riderId: "rider-4", displayName: "Luka", role: Role.rider, isSweep: true, colorArgb: nil, motorcycle: nil),
    ]

    /// Metres behind the leader. The leader is the reference point, so it is always zero.
    @Published private(set) var metresBehind: [String: Double] = [
        "rider-1": 0, "rider-2": 120, "rider-3": 300, "rider-4": 600,
    ]

    /// Metres of ground a rider loses per tick. Negative means closing.
    ///
    /// Drift rather than a fixed distance, because the engine does not alert on a rider who is
    /// simply further back — it alerts on a gap that has been **growing continuously** for the
    /// grace period, and resets that clock the moment the gap stops growing. Somebody parked
    /// 2 km behind at a steady distance is riding their own pace, not in trouble, and the engine
    /// is right not to shout about them. A simulation that only moved riders once could never
    /// show any of that.
    @Published private(set) var drift: [String: Double] = [:]

    /// Riders whose phone has stopped reporting. Their last sample simply stops being refreshed,
    /// which is exactly what a lost signal looks like to the engine.
    @Published private(set) var silent: Set<String> = []

    @Published private(set) var roomState: RoomState = RoomState.riding

    /// The finished ride, once there is one. Nil while it is still going.
    @Published private(set) var summary: RideSummary?
    @Published private(set) var assessments: [RiderAssessment] = []
    @Published private(set) var announcements: [Announcement] = []
    @Published private(set) var alertCount = 0

    /// Seconds of ride time elapsed. On screen because a simulation whose clock you cannot see
    /// gives you no way to tell a quiet ride from a stopped one.
    @Published private(set) var rideSeconds: Int = 0

    /// Ride seconds advanced per real second.
    ///
    /// Not cosmetic. The engine holds a new rider clear of alerts for a 150-second joining grace and
    /// then wants 60 seconds of a sustained gap before it says anything — both sensible on a real
    /// ride and both far longer than anybody will sit watching a simulation. At ten to one a gap
    /// opens, is confirmed and is announced inside half a minute of real time, and the engine's own
    /// timings are untouched.
    private let rideSecondsPerTick: TimeInterval = 10

    /// How far the front of the group has got along the road, in metres.
    ///
    /// The group used to stand still. `metresBehind` is measured from the leader, and with the
    /// leader as the origin nobody's coordinates ever changed — which is all the alert engine
    /// needs, since it only ever looks at the gaps between riders, but it means every trace is
    /// one point repeated and a summary of it is a column of zeros. The group rides now. Because
    /// everyone advances by the same amount every tick the gaps are untouched, so the engine sees
    /// exactly the ride it saw before.
    private var metresRidden: Double = 0

    /// What the group rides at, in metres per second — 14 m/s is a little over 50 km/h.
    private let groupSpeedMps: Double = 14

    /// What each rider's phone last actually sent, and the ride second it sent it.
    ///
    /// A phone that has stopped reporting does not send a stale fix, it sends nothing at all, and
    /// the engine keeps reading the last one it did get. Freezing the whole sample rather than
    /// only its timestamp matters now that the group moves: a frozen clock on a position that
    /// kept advancing is a reading no real device produces.
    private var reported: [String: (sample: RiderSample, at: TimeInterval)] = [:]

    /// Where each rider was at the end of the previous tick, for working out how fast they went.
    private var lastAlong: [String: Double] = [:]

    /// What each phone reported, in order, once each. The summary is built from exactly this and
    /// nothing else — it is the record a real app would keep, and a rider who goes quiet leaves
    /// a hole in theirs rather than a straight line through it.
    private var trace: [String: [TracePoint]] = [:]

    private var elapsed: TimeInterval = 0
    private var timer: Timer?

    func start() {
        members.forEach { lastAlong[$0.riderId] = -(metresBehind[$0.riderId] ?? 0) }
        advance()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.advance() }
        }
    }

    func stop() { timer?.invalidate(); timer = nil }

    // MARK: - controls

    func setState(_ state: RoomState) {
        roomState = state
        advance()
        // Summarised once, on the way into ENDED, rather than on every tick. Nothing is added to
        // a trace after the ride is over, so the answer cannot change, and republishing an
        // identical summary once a second would rebuild the screen for nothing.
        summary = state == RoomState.ended ? summariser.summarise(roomId: roomId, traces: trace) : nil
    }

    /// Start losing ground, keep losing it until told otherwise.
    func dropBack(_ riderId: String) {
        drift[riderId] = 120
        advance()
    }

    /// Close the gap again. Stops at the front rather than overtaking the leader.
    func catchUp(_ riderId: String) {
        drift[riderId] = -200
        advance()
    }

    func holdPosition(_ riderId: String) {
        drift[riderId] = 0
        advance()
    }

    func toggleSilent(_ riderId: String) {
        if silent.contains(riderId) { silent.remove(riderId) } else { silent.insert(riderId) }
        advance()
    }

    // MARK: - the tick

    /// Advances one second and asks the shared engine what it makes of the result.
    private func advance() {
        // Ride time runs while the ride does. It used to run always, which nothing noticed until
        // the summary went on screen beside it and the two numbers disagreed: a clock still
        // counting up next to a finished ride's total is two answers to one question.
        if roomState.sharesLocation {
            elapsed += rideSecondsPerTick
            rideSeconds = Int(elapsed)
        }
        // Ground, though, is only covered while actually riding. A paused group stands where it
        // is, which is the stopped time the summary reports separately from the average.
        if roomState == RoomState.riding {
            metresRidden += groupSpeedMps * rideSecondsPerTick
        }

        for member in members where member.riderId != selfId {
            let moved = (metresBehind[member.riderId] ?? 0) + (drift[member.riderId] ?? 0)
            metresBehind[member.riderId] = max(0, moved)
            // Once somebody is back with the group there is nothing left to close.
            if moved <= 0 { drift[member.riderId] = 0 }
        }
        let now = instant(at: elapsed)

        var samples: [String: RiderSample] = [:]
        for member in members {
            let id = member.riderId
            let along = metresRidden - (metresBehind[id] ?? 0)
            // Speed is what this rider actually did this tick, not a constant. The summary
            // believes the reported figure — `maxSpeedMps` prefers it, because an instantaneous
            // reading is what a top speed wants — so a rider crawling backwards through the
            // group while their phone insists on 50 km/h would finish the ride with a top speed
            // they never rode.
            let speedMps = max(0, along - (lastAlong[id] ?? along)) / rideSecondsPerTick
            lastAlong[id] = along

            let fresh = RiderSample(
                riderId: id,
                location: position(metresAlong: along),
                speedMps: KotlinFloat(value: Float(speedMps)),
                at: now,
                reportingInterval: nil
            )
            // Nothing arrives from a phone that has gone quiet, so the engine goes on reading the
            // last sample that did arrive, and the growing distance between its timestamp and
            // `now` is what it reads as a lost signal.
            let stale = silent.contains(id) ? reported[id] : nil
            samples[id] = stale?.sample ?? fresh

            guard stale == nil else { continue }
            reported[id] = (sample: fresh, at: elapsed)

            // Recorded once per fix, and only while the room is sharing location. A rider who
            // went quiet therefore leaves a hole in their trace rather than a run of duplicates
            // — which is the coverage gap `RideSummariser` refuses to draw a straight line
            // across, reached through the button that causes it rather than fabricated.
            if roomState.sharesLocation {
                trace[id, default: []].append(
                    TracePoint(
                        at: fresh.at,
                        location: fresh.location,
                        speedMps: KotlinDouble(value: Double(speedMps))
                    )
                )
            }
        }

        let result = session.tick(
            tick: SessionTick(
                now: now,
                roomState: roomState,
                members: members,
                samples: samples,
                route: nil,
                batteryPercent: nil,
                events: [],
                crashSignals: []
            ),
            nameOf: { [members] id in
                members.first { $0.riderId == id }?.displayName ?? id
            }
        )

        assessments = result.assessments
        alertCount += result.alerts.count
        // Newest first, and bounded: a long ride would otherwise grow this without limit.
        announcements = (result.announcements.reversed() + announcements).prefix(20).map { $0 }
    }

    /// Everyone on one line heading north, spaced by how far behind they are.
    ///
    /// A straight line is enough: the engine measures along a route when it has one and falls back
    /// to straight-line distance when it does not, and this first version supplies no route.
    private func position(metresAlong: Double) -> LatLng {
        let metresPerDegree = 111_320.0
        return LatLng(latitude: 41.7 + metresAlong / metresPerDegree, longitude: 44.8)
    }

    private func instant(at seconds: TimeInterval) -> Kotlinx_datetimeInstant {
        Kotlinx_datetimeInstant.companion.fromEpochMilliseconds(
            epochMilliseconds: Int64(1_780_000_000_000 + Int(seconds) * 1000)
        )
    }

    func name(of riderId: String) -> String {
        members.first { $0.riderId == riderId }?.displayName ?? riderId
    }

    var roster: [Member] { members }
}
