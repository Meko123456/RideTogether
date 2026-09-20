import SwiftUI

@main
struct RideTogetherApp: App {
    @StateObject private var ride = RideStore()

    var body: some Scene {
        WindowGroup {
            RideView()
                .environmentObject(ride)
        }
    }
}
