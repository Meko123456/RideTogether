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
