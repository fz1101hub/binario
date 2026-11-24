import Foundation
import XCTest
@testable import BinarioKit

/// The shared rules of Android's smaller features on the iPhone, read as Android's tests read them:
/// the CO2 estimate (contracts/shared-rules/v1/co2-estimate-v1.json), the personal punctuality
/// (personal-punctuality-v1.json) and the train type groups of «Tipi di treno»
/// (train-type-groups-v1.json); with the shared tables they use and the saved timetables' JSON.
final class PersonalFeaturesContractTests: XCTestCase {
    private func point(_ value: Any?) -> Co2Point? {
        guard let pair = value as? [Any], pair.count == 2,
              let latitude = SharedRuleFile.double(pair[0]), let longitude = SharedRuleFile.double(pair[1]) else { return nil }
        return Co2Point(latitude: latitude, longitude: longitude)
    }

    // MARK: CO2

    func testCo2GreatCirclesAndPlaneFactor() throws {
        let rules = try SharedRuleFile.load("co2-estimate-v1.json")
        let tolerance = try XCTUnwrap(SharedRuleFile.double(rules["tolerance"]))
        for item in SharedRuleFile.objects(rules["greatCircle"]) {
            let from = try XCTUnwrap(point(item["from"]))
            let to = try XCTUnwrap(point(item["to"]))
            let expected = try XCTUnwrap(SharedRuleFile.double(item["expectedKm"]))
            XCTAssertEqual(Co2Estimator.greatCircleKm(from, to), expected, accuracy: tolerance, SharedRuleFile.string(item["name"]) ?? "")
        }
        for item in SharedRuleFile.objects(rules["planeFactor"]) {
            let km = try XCTUnwrap(SharedRuleFile.double(item["km"]))
            let expected = try XCTUnwrap(SharedRuleFile.double(item["expected"]))
            XCTAssertEqual(Co2Estimator.planeGramsPerPkm(km), expected, accuracy: tolerance)
        }
    }

    func testCo2Estimates() throws {
        let rules = try SharedRuleFile.load("co2-estimate-v1.json")
        let tolerance = try XCTUnwrap(SharedRuleFile.double(rules["tolerance"]))
        let cases = SharedRuleFile.objects(rules["estimates"])
        XCTAssertEqual(cases.count, 6)
        for item in cases {
            let name = SharedRuleFile.string(item["name"]) ?? ""
            let legs: [(Co2Point?, Co2Point?)] = (item["legs"] as? [Any] ?? []).map { leg in
                let pair = leg as? [Any] ?? []
                return (pair.count > 0 ? point(pair[0]) : nil, pair.count > 1 ? point(pair[1]) : nil)
            }
            let estimate = Co2Estimator.estimate(points: legs)
            guard let expected = item["expected"] as? [String: Any] else {
                XCTAssertNil(estimate, name)
                continue
            }
            let value = try XCTUnwrap(estimate, name)
            XCTAssertEqual(value.trainKg, try XCTUnwrap(SharedRuleFile.double(expected["trainKg"])), accuracy: tolerance, name)
            XCTAssertEqual(value.carKg, try XCTUnwrap(SharedRuleFile.double(expected["carKg"])), accuracy: tolerance, name)
            XCTAssertEqual(value.planeKg, try XCTUnwrap(SharedRuleFile.double(expected["planeKg"])), accuracy: tolerance, name)
            XCTAssertEqual(value.railKm, try XCTUnwrap(SharedRuleFile.double(expected["railKm"])), accuracy: tolerance, name)
        }
    }

    func testCo2KilogramsAsShown() throws {
        let rules = try SharedRuleFile.load("co2-estimate-v1.json")
        for item in SharedRuleFile.objects(rules["displayKg"]) {
            let kg = try XCTUnwrap(SharedRuleFile.double(item["kg"]))
            let locale = Locale(identifier: try XCTUnwrap(SharedRuleFile.string(item["language"])))
            XCTAssertEqual(Co2Estimator.displayKg(kg, locale: locale), SharedRuleFile.string(item["expected"]))
        }
    }

    // MARK: Personal punctuality

    func testPunctualityTrainKeysAndBuckets() throws {
        let rules = try SharedRuleFile.load("personal-punctuality-v1.json")
        for item in SharedRuleFile.objects(rules["trainKeys"]) {
            let input = try XCTUnwrap(SharedRuleFile.string(item["input"]))
            XCTAssertEqual(PunctualityHistory.trainKey(input), SharedRuleFile.string(item["expected"]))
        }
        let day = try XCTUnwrap(PlannedLocalDate(iso: "2026-11-24"))
        for item in SharedRuleFile.objects(rules["buckets"]) {
            let observation = PunctualityObservation(
                serviceDate: day, trainNumber: "2153", operatorName: "",
                delayMinutes: try XCTUnwrap(SharedRuleFile.int(item["delayMinutes"])),
                cancelled: item["cancelled"] as? Bool ?? false
            )
            XCTAssertEqual(PunctualityHistory.bucket(of: observation).rawValue, SharedRuleFile.string(item["expected"]))
        }
    }

    func testPunctualityStatistics() throws {
        let rules = try SharedRuleFile.load("personal-punctuality-v1.json")
        let cases = SharedRuleFile.objects(rules["stats"])
        XCTAssertEqual(cases.count, 4)
        for item in cases {
            let name = SharedRuleFile.string(item["name"]) ?? ""
            // As read from a stored history: the JSON form of Android's history and of the backup.
            let history = PunctualityHistory.observations(fromJSON: item["history"] as? [Any] ?? [])
            let train = try XCTUnwrap(SharedRuleFile.string(item["train"]))
            let stats = PunctualityHistory.stats(history, trainNumber: train)
            guard let expected = item["expected"] as? [String: Any] else {
                XCTAssertNil(stats, name)
                continue
            }
            let value = try XCTUnwrap(stats, name)
            XCTAssertEqual(value.count, SharedRuleFile.int(expected["count"]), name)
            XCTAssertEqual(value.onTimePercent, SharedRuleFile.int(expected["onTimePercent"]), name)
            XCTAssertEqual(value.averageDelayMinutes, SharedRuleFile.int(expected["averageDelayMinutes"]), name)
            XCTAssertEqual(value.cancelledCount, SharedRuleFile.int(expected["cancelledCount"]), name)
            XCTAssertEqual(value.latePercent, SharedRuleFile.int(expected["latePercent"]), name)
            let distribution = expected["distribution"] as? [String: Any] ?? [:]
            for bucket in PunctualityBucket.allCases {
                XCTAssertEqual(value.distribution[bucket], SharedRuleFile.int(distribution[bucket.rawValue]), "\(name) \(bucket.rawValue)")
            }
            if let worst = expected["worstStop"] as? [String: Any] {
                XCTAssertEqual(value.worstStop?.station, SharedRuleFile.string(worst["station"]), name)
                XCTAssertEqual(value.worstStop?.averageIncreaseMinutes, SharedRuleFile.int(worst["averageIncreaseMinutes"]), name)
                XCTAssertEqual(value.worstStop?.samples, SharedRuleFile.int(worst["samples"]), name)
            } else {
                XCTAssertNil(value.worstStop, name)
            }
            XCTAssertEqual(value.history.map { $0.serviceDate.iso }, expected["historyDates"] as? [String], name)
        }
    }

    func testPunctualityHistoryKeepsOnePerDayWithinTheRetention() throws {
        let today = try XCTUnwrap(PlannedLocalDate(iso: "2026-11-24"))
        let old = try XCTUnwrap(today.adding(days: -SharedData.PersonalPunctuality.retentionDays))
        let observations = [
            PunctualityObservation(serviceDate: today, trainNumber: "reg 2153", operatorName: "Trenitalia", delayMinutes: 3, cancelled: false),
            PunctualityObservation(serviceDate: today, trainNumber: "REG 2153", operatorName: "Trenitalia", delayMinutes: 9, cancelled: false),
            PunctualityObservation(serviceDate: old, trainNumber: "REG 2153", operatorName: "Trenitalia", delayMinutes: 1, cancelled: false),
        ]
        let bounded = PunctualityHistory.bound(observations, today: today)
        XCTAssertEqual(bounded.count, 1)
        XCTAssertEqual(bounded.first?.delayMinutes, 9)
        XCTAssertEqual(bounded.first?.trainNumber, "REG 2153")
        XCTAssertEqual(PunctualityHistory.decode(PunctualityHistory.encode(bounded)), bounded)
    }

    // MARK: Train type groups

    func testTrainTypeGroups() throws {
        let rules = try SharedRuleFile.load("train-type-groups-v1.json")
        let cases = SharedRuleFile.objects(rules["cases"])
        XCTAssertEqual(cases.count, 17)
        for item in cases {
            let group = TrainTypeGroups.group(
                label: SharedRuleFile.string(item["label"]) ?? "",
                operatorID: SharedRuleFile.string(item["operatorId"]),
                isBus: item["bus"] as? Bool ?? false
            )
            XCTAssertEqual(group?.rawValue, SharedRuleFile.string(item["expected"]), "\(item)")
        }
        XCTAssertEqual(TrainTypeGroup.allCases.map(\.rawValue), SharedData.TrainTypeGroups.groups)
        let both: Set<TrainTypeGroup> = [.italo, .highSpeed]
        XCTAssertEqual(TrainTypeGroups.decode(TrainTypeGroups.encode(both)), both)
        XCTAssertEqual(TrainTypeGroups.encode(both), "HIGH_SPEED,ITALO")
        XCTAssertEqual(TrainTypeGroups.operatorID("SBB CFF FFS"), "sbb")
        XCTAssertEqual(TrainTypeGroups.operatorID("ÖBB"), "oebb")
        XCTAssertNil(TrainTypeGroups.operatorID("Trenitalia"))
    }

    // MARK: Official links and saved timetables

    func testOfficialLinksAreTheSharedTable() throws {
        XCTAssertEqual(OfficialLinks.all.map(\.id), SharedData.OfficialLinks.operators.map(\.id))
        let trenitalia = try XCTUnwrap(OfficialLinks.operatorLinks(id: "trenitalia"))
        XCTAssertEqual(trenitalia.links.last?.kind, .strikes)
        for operatorLinks in OfficialLinks.all {
            for link in operatorLinks.links where link.kind != .strikes {
                let host = try XCTUnwrap(link.url.host)
                XCTAssertTrue(operatorLinks.domains.contains { host == $0 || host.hasSuffix("." + $0) }, link.url.absoluteString)
                XCTAssertEqual(link.url.scheme, "https")
            }
        }
        XCTAssertEqual(OfficialLinks.operatorOf(operatorName: "TRENORD")?.id, "trenord")
        XCTAssertNil(OfficialLinks.operatorOf(operatorName: "Trenitalia Tper"))
        XCTAssertEqual(OfficialLinks.purchaseSite(operatorName: "Italo")?.id, "italo")
        XCTAssertEqual(OfficialLinks.SalaBlu.dialURL("800 90 60 60")?.absoluteString, "tel:800906060")
    }

    func testSavedTimetablesReadAndroidsJSON() throws {
        let json = """
        [{"id":"t1","name":"Casa","origin":"Milano Centrale","destination":"Pavia","date":"2026-11-24","from":"07:30",
          "savedAt":1795500000000,"rows":[{"departure":"07:42","arrival":"08:10","duration":28,"trains":"REG 2153",
          "changes":0,"platform":"7","scheduledPlatform":"5","source":"Orario Trenitalia","fetchedAt":0,"scheduledOnly":true},
          {"departure":"bad"}]},
         {"id":"t1","origin":"A","destination":"B","date":"2026-11-24","from":"07:30","rows":[{"departure":"09:00","trains":"","changes":0,"source":""}]},
         {"id":"t2","origin":"A","destination":"B","date":"2026-11-24","from":"7:30","rows":[]}]
        """
        let decoded = SavedTimetableCodec.decode(Data(json.utf8))
        XCTAssertEqual(decoded.count, 1)
        let timetable = try XCTUnwrap(decoded.first)
        XCTAssertEqual(timetable.rows.count, 1)
        XCTAssertEqual(timetable.rows.first?.platform, "7")
        XCTAssertEqual(timetable.rows.first?.durationMinutes, 28)
        XCTAssertEqual(SavedTimetableCodec.decode(SavedTimetableCodec.encode(decoded)), decoded)
        XCTAssertEqual(SavedTimetableCodec.renamed(decoded, id: "t1", name: "  ").first?.name, "Casa")
    }

    // MARK: Local backup

    func testLocalBackupRoundTripAndAndroidsFile() throws {
        let milano = StationIdentity(id: "S01700", name: "Milano Centrale", railwayCode: "S01700")
        let pavia = StationIdentity(id: "S01860", name: "Pavia", railwayCode: "S01860")
        let route = try SavedRoute(id: SavedRoute.routeIdentifier(origin: milano, destination: pavia), name: "Casa", origin: milano, destination: pavia)
        let train = try FavouriteTrain(
            number: "2153", operatorName: "Trenord", category: "REG", terminus: "Pavia", scheduledDeparture: "07:42",
            bikesAllowed: nil, origin: milano, destination: pavia, savedAt: Date(timeIntervalSince1970: 0)
        )
        let backup = LocalBackup(
            routes: [route], favouriteStations: [pavia], favouriteTrains: [train], trainNames: [train.id: "Il mio treno"],
            punctualityHistory: [], savedTimetables: []
        )
        let data = try LocalBackupCodec.encode(backup) { Station(id: $0.id, name: $0.name, railwayCode: $0.railwayCode) }.get()
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(root["kind"] as? String, SharedData.LocalBackup.kind)
        XCTAssertEqual(root["schemaVersion"] as? Int, SharedData.LocalBackup.schemaVersion)
        let read = try LocalBackupCodec.decode(data) { _ in nil }.get()
        // The file carries no creation date: an imported route is created again.
        XCTAssertEqual(read.routes.map(\.id), [route.id])
        XCTAssertEqual(read.routes.map(\.name), ["Casa"])
        XCTAssertEqual(read.routes.first?.origin, milano)
        XCTAssertEqual(read.favouriteStations, [pavia])
        XCTAssertEqual(read.favouriteTrains.map(\.id), [train.id])
        XCTAssertEqual(read.trainNames[train.id], "Il mio treno")

        // Android's file: its routes and favourite stations; its favourite trains lack the iPhone's keys.
        let android = """
        {"schemaVersion":2,"kind":"binario-local-backup","routes":[{"id":"r1","name":"Lavoro","originId":"S01700","destinationId":"S01860","pinned":true}],
         "favouriteStationIds":["S01860"],"monitoredServices":[{"number":"2153","label":"REG 2153"}],"stationMonitors":[],"commuterAlerts":[],
         "stations":[{"id":"S01700","name":"Milano Centrale","city":"Milano"},{"id":"S01860","name":"Pavia","city":"Pavia"}],
         "mobility":{"transferMarginMinutes":10,"stepFreePreferred":false,"compactTimetablePreferred":false}}
        """
        let fromAndroid = try LocalBackupCodec.decode(Data(android.utf8)) { _ in nil }.get()
        XCTAssertEqual(fromAndroid.routes.map(\.name), ["Lavoro"])
        XCTAssertEqual(fromAndroid.favouriteStations.map(\.id), ["S01860"])
        // Without operator and destination (an older Android file) a favourite cannot be made.
        XCTAssertTrue(fromAndroid.favouriteTrains.isEmpty)
        let withNames = android.replacingOccurrences(
            of: "{\"number\":\"2153\",\"label\":\"REG 2153\"}",
            with: "{\"number\":\"2153\",\"label\":\"Trenord · Pavia\",\"origin\":\"Milano Centrale\",\"scheduledDeparture\":\"07:42\",\"destination\":\"Pavia\",\"operator\":\"Trenord\",\"category\":\"REGIONALE\"}"
        )
        let names = ["Milano Centrale": milano, "Pavia": pavia]
        let named = try LocalBackupCodec.decode(Data(withNames.utf8), catalogue: { _ in nil }, stationNamed: { name in
            names[name].map { Station(id: $0.id, name: $0.name, railwayCode: $0.railwayCode) }
        }).get()
        XCTAssertEqual(named.favouriteTrains.map(\.category), ["REG"])
        XCTAssertEqual(named.favouriteTrains.first?.destination, pavia)
        XCTAssertNil(fromAndroid.savedTimetables)
        XCTAssertEqual(LocalBackupCodec.decode(Data("{\"kind\":\"other\"}".utf8)) { _ in nil }, .failure(.unreadable))
    }
}
