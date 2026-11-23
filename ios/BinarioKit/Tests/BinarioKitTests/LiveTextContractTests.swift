import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/live-text-v1.json: the explicit cancellation of a live row and the
/// operator of an RFI monitor row, read from the same file by the Android tests (the patterns stay
/// in each language's code).
final class LiveTextContractTests: XCTestCase {
    func testExplicitCancellationWordsAreTheSharedOnes() throws {
        for item in try contract().explicitCancellation {
            XCTAssertEqual(LiveText.hasExplicitCancellationStatus(item.text), item.cancelled, item.text)
        }
    }

    func testTheOperatorOfAnRfiRowIsTheSharedOne() throws {
        for item in try contract().rfiRowOperator {
            let row = RfiMonitorRow(
                number: item.number, destination: "Roma Termini", scheduled: "08:00", publishedDelay: nil, cancelled: false,
                lateWithoutMinutes: false, platform: nil, carrier: item.carrier, category: item.category
            )
            XCTAssertEqual(row.operatorID, item.operatorID, "\(item.carrier) \(item.category) \(item.number)")
        }
    }

    // MARK: - The contract file

    private struct Rules: Decodable {
        let explicitCancellation: [CancellationCase]
        let rfiRowOperator: [OperatorCase]
    }

    private struct CancellationCase: Decodable {
        let text: String
        let cancelled: Bool
    }

    private struct OperatorCase: Decodable {
        let carrier: String
        let category: String
        let number: String
        let operatorID: String?

        enum CodingKeys: String, CodingKey {
            case carrier, category, number
            case operatorID = "operator"
        }
    }

    private func contract() throws -> Rules {
        let url = repositoryRoot.appendingPathComponent("contracts/shared-rules/v1/live-text-v1.json")
        return try JSONDecoder().decode(Rules.self, from: Data(contentsOf: url))
    }

    private var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}
