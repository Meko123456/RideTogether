import SwiftUI
import Shared

/// The ride, as the shared engine sees it.
///
/// The order is the order a rider cares about: what the group is doing, who is in trouble, and
/// what the app would have said out loud. Once the ride has ended the totals go straight under
/// the state, above the live sections, because at that point they are the only thing left worth
/// reading.
struct RideView: View {
    @EnvironmentObject private var ride: RideStore

    var body: some View {
        NavigationStack {
            List {
                stateSection
                if let summary = ride.summary {
                    SummarySection(summary: summary, nameOf: ride.name(of:))
                }
                ridersSection
                announcementsSection
            }
            .navigationTitle("Sunday run")
            .onAppear { ride.start() }
            .onDisappear { ride.stop() }
        }
    }

    private var stateSection: some View {
        Section("The ride") {
            Picker("State", selection: Binding(
                get: { ride.roomState },
                set: { ride.setState($0) }
            )) {
                Text("Lobby").tag(RoomState.lobby)
                Text("Riding").tag(RoomState.riding)
                Text("Paused").tag(RoomState.paused)
                Text("Ended").tag(RoomState.ended)
            }
            .pickerStyle(.segmented)

            LabeledContent("Ride time", value: "\(ride.rideSeconds / 60)m \(ride.rideSeconds % 60)s")
            LabeledContent("Alerts raised", value: "\(ride.alertCount)")
            Text("Ride time runs at ten times real time, so the engine's grace periods play out in seconds rather than minutes.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var ridersSection: some View {
        Section("Riders") {
            ForEach(ride.roster, id: \.riderId) { member in
                RiderRow(
                    member: member,
                    assessment: ride.assessments.first { $0.riderId == member.riderId },
                    isSilent: ride.silent.contains(member.riderId),
                    metresBehind: ride.metresBehind[member.riderId] ?? 0,
                    drift: ride.drift[member.riderId] ?? 0,
                    onDropBack: { ride.dropBack(member.riderId) },
                    onCatchUp: { ride.catchUp(member.riderId) },
                    onToggleSilent: { ride.toggleSilent(member.riderId) }
                )
            }
        }
    }

    private var announcementsSection: some View {
        Section("What the app would say") {
            if ride.announcements.isEmpty {
                Text("Nothing to say. That is the ride going well.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(Array(ride.announcements.enumerated()), id: \.offset) { _, announcement in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(announcement.text)
                        Text(String(describing: announcement.priority))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}

/// What the ride was, once it is over.
///
/// Every number here is `RideSummary`'s. This view converts metres per second to km/h and metres
/// to kilometres and does no other arithmetic: which stops counted, what a fix has to imply
/// before it is thrown away, whether the average should include the time spent stationary — all
/// of that is `RideSummariser`'s, decided once in Kotlin and already tested there.
private struct SummarySection: View {
    let summary: RideSummary
    let nameOf: (String) -> String

    var body: some View {
        Section("Ride summary") {
            if summary.isEmpty {
                Text("Nothing was recorded. The ride has to run before there is anything to total up.")
                    .foregroundStyle(.secondary)
            } else {
                HStack(alignment: .top) {
                    Stat(label: "Distance", value: distance(summary.distanceMeters), id: "summary-distance")
                    Spacer()
                    Stat(label: "Elapsed", value: duration(summary.elapsedSeconds), id: "summary-elapsed")
                    Spacer()
                    Stat(label: "Riders", value: "\(summary.riders.count)", id: "summary-riders")
                }
                .padding(.vertical, 4)

                Text("Distance is how far the furthest rider actually rode. Averaging the group " +
                     "would describe a ride nobody took.")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                // In the order the summariser returned them, which is furthest first.
                ForEach(summary.riders, id: \.riderId) { rider in
                    SummaryRow(rider: rider, name: nameOf(rider.riderId))
                }
            }
        }
    }
}

/// One rider's ride.
private struct SummaryRow: View {
    let rider: RiderSummary
    let name: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(name).font(.headline)
                Spacer()
                Text(distance(rider.distanceMeters))
                    .accessibilityIdentifier("summary-distance-\(rider.riderId)")
            }
            // Moving average and stopped time side by side, for the reason RideSummariser gives:
            // an average that includes the lunch stop reads as though the app mis-measured the
            // ride, and hiding the stopped time to make the average look better would be worse.
            Text("avg \(speed(rider.averageMovingSpeedMps)) · top \(speed(rider.maxSpeedMps))")
                .font(.caption)
                .accessibilityIdentifier("summary-speed-\(rider.riderId)")
            Text("\(duration(rider.movingSeconds)) riding · \(duration(rider.stoppedSeconds)) stopped · \(stops(rider.stopCount))")
                .font(.caption)
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("summary-time-\(rider.riderId)")
            if rider.discardedPoints > 0 {
                Text("\(rider.discardedPoints) fixes discarded as implausible, so these numbers are approximate.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("summary-discarded-\(rider.riderId)")
            }
        }
        .padding(.vertical, 4)
    }
}

private struct Stat: View {
    let label: String
    let value: String
    let id: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.title3).fontWeight(.semibold).accessibilityIdentifier(id)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// Distance the way a rider says it: metres under a kilometre, one decimal above.
private func distance(_ metres: Double) -> String {
    metres < 1_000 ? "\(Int(metres)) m" : String(format: "%.1f km", metres / 1_000)
}

/// `1h 24m`, or `7m` — never `0h 07m`, which reads like a clock rather than a duration.
private func duration(_ seconds: Int64) -> String {
    let minutes = seconds / 60
    let hours = minutes / 60
    if hours > 0 { return "\(hours)h \(minutes % 60)m" }
    if minutes > 0 { return "\(minutes)m" }
    // Under a minute reads as "0m" otherwise, which looks like a failure rather than a short ride.
    return "\(seconds)s"
}

/// "1 stop", not "1 stops". A summary that cannot count to one does not inspire confidence in
/// the rest of its numbers.
private func stops(_ count: Int32) -> String {
    count == 1 ? "1 stop" : "\(count) stops"
}

private func speed(_ mps: KotlinDouble?) -> String {
    guard let mps else { return "—" }
    return "\(Int(mps.doubleValue * 3.6)) km/h"
}

/// One rider: who they are, what the engine makes of them, and the controls to put them in trouble.
private struct RiderRow: View {
    let member: Member
    let assessment: RiderAssessment?
    let isSilent: Bool
    let metresBehind: Double
    let drift: Double
    let onDropBack: () -> Void
    let onCatchUp: () -> Void
    let onToggleSilent: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(member.displayName).font(.headline)
                if member.isSweep {
                    Text("sweep").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Text(statusText)
                    .font(.subheadline)
                    .foregroundStyle(statusColour)
                    .accessibilityIdentifier("status-\(member.riderId)")
            }

            // Stated in metres as well as by colour: colour alone is not a signal everybody can read.
            Text("\(Int(metresBehind)) m behind\(driftLabel)\(isSilent ? " · not reporting" : "")")
                .accessibilityIdentifier("gap-\(member.riderId)")
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack(spacing: 12) {
                Button("Drop back", action: onDropBack)
                    .accessibilityIdentifier("dropBack-\(member.riderId)")
                Button("Catch up", action: onCatchUp)
                    .accessibilityIdentifier("catchUp-\(member.riderId)")
                Button(isSilent ? "Restore signal" : "Lose signal", action: onToggleSilent)
                    .accessibilityIdentifier("signal-\(member.riderId)")
            }
            .buttonStyle(.bordered)
            .font(.caption)
        }
        .padding(.vertical, 4)
    }

    /// Named as well as implied by the numbers moving, since a still screenshot shows neither.
    private var driftLabel: String {
        if drift > 0 { return " · losing ground" }
        if drift < 0 { return " · closing" }
        return ""
    }

    private var statusText: String {
        guard let assessment else { return "—" }
        switch assessment.status {
        case RiderStatus.active: return "with the group"
        case RiderStatus.fallingBehind: return "falling behind"
        case RiderStatus.stopped: return "stopped"
        case RiderStatus.signalLost: return "signal lost"
        case RiderStatus.possibleIncident: return "possible incident"
        default: return "—"
        }
    }

    private var statusColour: Color {
        guard let assessment else { return .secondary }
        switch assessment.status {
        case RiderStatus.active: return .green
        case RiderStatus.fallingBehind: return .orange
        default: return .red
        }
    }
}
