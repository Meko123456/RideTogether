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
}
