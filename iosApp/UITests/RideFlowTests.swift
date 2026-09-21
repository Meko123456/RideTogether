import XCTest

/// Proves the app works, not merely that it compiles.
///
/// The distinction matters here more than usual. Everything this app decides is decided in Kotlin,
/// so a build that succeeds says the two languages agree about types and says nothing at all about
/// whether a tap reaches the engine and the answer comes back. This fleet has twice shipped
/// something that compiled and did nothing.
final class RideFlowTests: XCTestCase {

    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    func testTheRosterComesFromTheSharedModule() {
        // Names and the sweep flag are held in Kotlin. Seeing them means the framework loaded, the
        // members list crossed the bridge and SwiftUI drew it.
        XCTAssertTrue(app.staticTexts["You"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Dato"].exists)
        XCTAssertTrue(app.staticTexts["Luka"].exists)
        XCTAssertTrue(app.staticTexts["sweep"].exists)
    }

    func testEveryRiderStartsWithTheGroup() {
        let status = app.staticTexts["status-rider-2"]
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        XCTAssertEqual(status.label, "with the group")
    }

    func testTheClockAdvances() {
        // A simulation whose clock is stopped would pass every other assertion here.
        let first = app.staticTexts["gap-rider-2"].firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 10))
        let before = first.label
        // Riders hold position by default, so the gap only moves once something makes it move.
        app.buttons["dropBack-rider-2"].tap()
        let changed = NSPredicate(format: "label != %@", before)
        expectation(for: changed, evaluatedWith: first, handler: nil)
        waitForExpectations(timeout: 15)
    }

    func testDroppingBackEventuallyReachesTheAlertEngine() {
        // The whole chain: a tap in Swift, a rising gap, the engine's joining grace and its
        // sixty seconds of sustained separation, and a verdict drawn back in SwiftUI. Ride time
        // runs at ten times real time, which is what makes this a test rather than a coffee break.
        app.buttons["dropBack-rider-2"].tap()

        let status = app.staticTexts["status-rider-2"]
        let fallsBehind = NSPredicate(format: "label == %@", "falling behind")
        expectation(for: fallsBehind, evaluatedWith: status, handler: nil)
        waitForExpectations(timeout: 90)
    }

    func testTheAnnouncerSpeaksAboutARiderWhoseSignalDrops() {
        // The Announcer is a separate shared component from the alert engine, and nothing else here
        // would notice if it produced silence, because every status label comes from the engine.
        //
        // A lost signal is the right alert to test it with, and the reason is the interesting part.
        // The falling-behind line is deliberately spoken *only to the rider who dropped back* —
        // everyone else sees it on screen — and in this simulation you are the leader, who cannot
        // fall behind themselves. So that line is unreachable here by design rather than by
        // accident, while a lost signal is announced to everybody.
        app.buttons["signal-rider-2"].tap()

        // First that the engine noticed, so a failure below points at the announcer rather than
        // leaving both suspects standing.
        let status = app.staticTexts["status-rider-2"]
        expectation(for: NSPredicate(format: "label == %@", "signal lost"), evaluatedWith: status, handler: nil)
        waitForExpectations(timeout: 60)

        // The announcements sit below the riders, and a SwiftUI List does not build rows it has not
        // been scrolled to, so they have to be brought on screen before they exist to query.
        app.swipeUp()
        app.swipeUp()

        // Asserted on the announcer's own wording rather than on the rider's name: "Dato" is
        // already on screen as a row heading, so a name match would pass without the announcer
        // ever running.
        let spoken = app.staticTexts.containing(
            NSPredicate(format: "label CONTAINS %@", "has lost signal")
        ).firstMatch
        expectation(for: NSPredicate(format: "exists == true"), evaluatedWith: spoken, handler: nil)
        waitForExpectations(timeout: 60)
    }

    func testCatchingUpClearsTheAlert() {
        app.buttons["dropBack-rider-2"].tap()
        let status = app.staticTexts["status-rider-2"]
        expectation(for: NSPredicate(format: "label == %@", "falling behind"), evaluatedWith: status, handler: nil)
        waitForExpectations(timeout: 90)

        // Hysteresis: the engine clears only on a real recovery, not a wobble, so this also proves
        // the clear threshold is reachable from the UI rather than only in a unit test.
        app.buttons["catchUp-rider-2"].tap()
        expectation(for: NSPredicate(format: "label == %@", "with the group"), evaluatedWith: status, handler: nil)
        waitForExpectations(timeout: 90)
    }

    // MARK: - the ride summary

    func testEndingTheRideSummarisesARideThatActuallyHappened() {
        rideThenEnd()

        // Split so a failure names one suspect rather than leaving all of them standing: that a
        // summary appeared at all, that it has a distance, and that the distance is a ride's
        // worth rather than a single fix.
        let distance = app.staticTexts["summary-distance"]
        XCTAssertTrue(distance.waitForExistence(timeout: 10), "ending the ride produced no summary")
        XCTAssertNotEqual(distance.label, "0 m", "the summariser totalled a ride that never moved")
        XCTAssertGreaterThan(metres(distance.label), 500, "ride too short: \(distance.label)")

        // Elapsed is the span of the recorded trace, worked out in Kotlin from its first and last
        // timestamps rather than from any clock Swift is keeping. It is zero unless fixes were
        // being recorded all the way through the ride instead of at the moment it ended.
        XCTAssertNotEqual(app.staticTexts["summary-elapsed"].label, "0s", "the trace has no span")
        XCTAssertEqual(app.staticTexts["summary-riders"].label, "4", "not every rider was summarised")
    }

    func testTheSummaryGivesEveryRiderTheGroupSpeed() {
        rideThenEnd()
        XCTAssertTrue(app.staticTexts["summary-distance"].waitForExistence(timeout: 10))

        // Nobody drops back here, so all four rode the same road at the same speed and the
        // summariser should say so about each of them. 50 km/h is its own arithmetic over the
        // trace — ground covered over time spent moving, stops excluded — and not an echo of the
        // speed the simulation reports, which it only ever uses for the top figure.
        let riders = ["rider-1", "rider-2", "rider-3", "rider-4"]
        let speeds = labels(of: riders.map { "summary-speed-\($0)" })
        for riderId in riders {
            let row = "summary-speed-\(riderId)"
            XCTAssertNotNil(speeds[row], "no summary row for \(riderId)")
            XCTAssertEqual(speeds[row], "avg 50 km/h · top 50 km/h", "wrong speeds for \(riderId)")
        }
    }

    func testARiderWhoDroppedBackDidNotRideTheSameRide() {
        XCTAssertTrue(app.staticTexts["You"].waitForExistence(timeout: 10), "the app never drew")
        app.buttons["dropBack-rider-2"].tap()
        Thread.sleep(forTimeInterval: 12)
        app.buttons["Ended"].tap()
        XCTAssertTrue(app.staticTexts["summary-distance"].waitForExistence(timeout: 10))

        let found = labels(of: ["summary-distance", "summary-distance-rider-1", "summary-distance-rider-2"])
        XCTAssertNotNil(found["summary-distance-rider-1"], "the leader is missing from the summary")
        XCTAssertNotNil(found["summary-distance-rider-2"], "the dropped rider is missing from the summary")

        // Per-rider distances are per-rider: the one who spent the ride losing ground covered
        // less of it, which no summary echoing a single group figure could show.
        XCTAssertNotEqual(
            found["summary-distance-rider-2"], found["summary-distance-rider-1"],
            "both riders were credited with the same distance"
        )
        // And the ride's own distance is the furthest any single rider rode — the leader's —
        // rather than an average of the two, which would describe a ride nobody took.
        XCTAssertEqual(
            found["summary-distance"], found["summary-distance-rider-1"],
            "the ride's distance is not the furthest rider's"
        )
    }

    func testPausingTheRideCountsAsStoppedTimeRatherThanRiding() {
        XCTAssertTrue(app.staticTexts["You"].waitForExistence(timeout: 10), "the app never drew")
        Thread.sleep(forTimeInterval: 6)

        // A paused group stands where it is, but the clock keeps running, because a fuel stop is
        // part of the ride. The summariser reads that as stopped time and — past the minute it
        // insists on before calling a halt a stop, which keeps traffic lights out of the total —
        // as one stop. Ride time runs at ten to one, so ten real seconds is comfortably clear
        // of the minute.
        app.buttons["Paused"].tap()
        Thread.sleep(forTimeInterval: 10)
        app.buttons["Ended"].tap()
        XCTAssertTrue(app.staticTexts["summary-distance"].waitForExistence(timeout: 10))

        let line = labels(of: ["summary-time-rider-1"])["summary-time-rider-1"]
        XCTAssertNotNil(line, "the leader is missing from the summary")
        // Split, because these fail for different reasons: the first if standing still was
        // booked as riding, the second if it was booked as stopped but never long enough to
        // count — and the two have nothing to do with each other.
        XCTAssertFalse(line?.contains("0s stopped") ?? true, "the pause was not stopped time: \(line ?? "")")
        XCTAssertTrue(line?.hasSuffix("· 1 stop") ?? false, "the pause was not one stop: \(line ?? "")")
    }

    // MARK: - helpers

    /// Rides for a while, then ends the ride.
    ///
    /// The wait is for elapsed time rather than for an event, which is deliberate: the thing
    /// being waited on *is* the ride happening. The group covers 140 m of road per tick and ticks
    /// once a second, so twelve seconds is well over a kilometre and the totals that follow are
    /// answers rather than zeros.
    private func rideThenEnd(seconds: TimeInterval = 12) {
        XCTAssertTrue(app.staticTexts["You"].waitForExistence(timeout: 10), "the app never drew")
        Thread.sleep(forTimeInterval: seconds)
        app.buttons["Ended"].tap()
    }

    /// Reads the labels of several elements, scrolling down until it has them.
    ///
    /// Two things force the sweep. A SwiftUI `List` does not build rows it has not been scrolled
    /// to, so anything below the fold does not exist to query until it has been. And the rider
    /// rows come back in the order `RideSummary` sorted them into — furthest first — which for a
    /// group that all rode the same distance is not an order a test can predict, so looking for
    /// each row where it ought to be would scroll straight past the ones out of place.
    private func labels(of identifiers: [String], swipes: Int = 6) -> [String: String] {
        var found: [String: String] = [:]
        for _ in 0...swipes {
            for id in identifiers where found[id] == nil {
                let element = app.staticTexts[id]
                if element.exists { found[id] = element.label }
            }
            if found.count == identifiers.count { break }
            app.swipeUp()
        }
        return found
    }

    /// `1.5 km` or `340 m` back to metres, so an assertion can be about the size of the ride
    /// rather than about how it happened to round.
    private func metres(_ label: String) -> Double {
        let parts = label.split(separator: " ")
        let value = Double(parts.first ?? "") ?? 0
        return parts.last == "km" ? value * 1_000 : value
    }
}
