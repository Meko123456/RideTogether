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

    private lazy var session = RideSession(
        selfId: selfId,
        alertConfig: AlertConfig.companion.Default,
        announceConfig: AnnounceConfig.companion.Default
    )

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

    private var elapsed: TimeInterval = 0
    private var lastSeen: [String: TimeInterval] = [:]
    private var timer: Timer?

    func start() {
        members.forEach { lastSeen[$0.riderId] = 0 }
        advance()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.advance() }
        }
    }

    func stop() { timer?.invalidate(); timer = nil }

    // MARK: - controls

    func setState(_ state: RoomState) { roomState = state; advance() }

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
        elapsed += rideSecondsPerTick
        rideSeconds = Int(elapsed)

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
            // A silent rider keeps the position and the timestamp it last reported, so the gap
            // between that and `now` is what the engine reads as a stale signal.
            if !silent.contains(id) { lastSeen[id] = elapsed }
            let seenAt = lastSeen[id] ?? elapsed
            samples[id] = RiderSample(
                riderId: id,
                location: position(metresBehind: metresBehind[id] ?? 0),
                speedMps: KotlinFloat(value: silent.contains(id) ? 0 : 14),
                at: instant(at: seenAt),
                reportingInterval: nil
            )
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
    private func position(metresBehind: Double) -> LatLng {
        let metresPerDegree = 111_320.0
        return LatLng(latitude: 41.7 - metresBehind / metresPerDegree, longitude: 44.8)
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
