import SwiftUI
import Shared

/// The ride, as the shared engine sees it.
///
/// Three sections, and the order is the order a rider cares about: what the group is doing, who is
/// in trouble, and what the app would have said out loud.
struct RideView: View {
    @EnvironmentObject private var ride: RideStore

    var body: some View {
        NavigationStack {
            List {
                stateSection
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
