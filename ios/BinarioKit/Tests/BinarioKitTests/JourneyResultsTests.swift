import XCTest
@testable import BinarioKit

/// The rules of the results of the journeys with changes, as Android's `JourneyChangeFilter`, `JourneyResultsFilter`
/// and `ResultsReliability.mostReliableFirst`.
final class JourneyResultsTests: XCTestCase {
    func testTheChangesFilterKeepsWhatAndroidKeeps() {
        XCTAssertEqual(JourneyChangeFilter.allCases.map(\.rawValue), ["ANY", "DIRECT", "UP_TO_ONE", "UP_TO_TWO"])
        XCTAssertEqual((0...3).map(JourneyChangeFilter.any.accepts), [true, true, true, true])
        XCTAssertEqual((0...3).map(JourneyChangeFilter.direct.accepts), [true, false, false, false])
        XCTAssertEqual((0...3).map(JourneyChangeFilter.upToOne.accepts), [true, true, false, false])
        XCTAssertEqual((0...3).map(JourneyChangeFilter.upToTwo.accepts), [true, true, true, false])
    }

    func testTheResultsOpenOnTheChipOfThePreferredRanking() {
        XCTAssertEqual(JourneyResultsFilter.forRanking(.fastest), .all)
        XCTAssertEqual(JourneyResultsFilter.forRanking(.fewestChanges), .fewestChanges)
        XCTAssertEqual(JourneyResultsFilter.forRanking(.leastWalking), .leastWalking)
    }

    func testMostReliableFirstMeasuredThenScoreToSixDecimalsThenMeanDelay() {
        let strong = EntryReliability(score: 0.9, meanDelayMinutes: 4)
        let weak = EntryReliability(score: 0.5, meanDelayMinutes: 1)
        XCTAssertEqual(EntryReliability.mostReliableFirst(strong, weak), true)
        XCTAssertEqual(EntryReliability.mostReliableFirst(weak, strong), false)
        XCTAssertEqual(EntryReliability.mostReliableFirst(weak, nil), true)
        XCTAssertEqual(EntryReliability.mostReliableFirst(nil, weak), false)
        XCTAssertNil(EntryReliability.mostReliableFirst(nil, nil))
        // Equal to six decimals: the lower mean delay first, then a tie.
        let later = EntryReliability(score: 0.9000001, meanDelayMinutes: 6)
        XCTAssertEqual(EntryReliability.mostReliableFirst(strong, later), true)
        XCTAssertEqual(EntryReliability.mostReliableFirst(later, strong), false)
        XCTAssertNil(EntryReliability.mostReliableFirst(strong, EntryReliability(score: 0.9, meanDelayMinutes: 4)))
    }
}
