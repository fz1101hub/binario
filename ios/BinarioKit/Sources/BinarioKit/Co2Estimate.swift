import Foundation

/// A station position, in degrees.
public struct Co2Point: Equatable, Sendable {
    public let latitude: Double
    public let longitude: Double

    public init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }
}

/// One vehicle leg between two stations of known position.
public struct Co2Leg: Equatable, Sendable {
    public let from: Co2Point
    public let to: Co2Point

    public init(from: Co2Point, to: Co2Point) {
        self.from = from
        self.to = to
    }
}

/// Kilograms of CO2e per passenger: the train journey, the same trip by car and by plane, and the
/// rail distance used.
public struct Co2Estimate: Equatable, Sendable {
    public let trainKg: Double
    public let carKg: Double
    public let planeKg: Double
    public let railKm: Double

    public init(trainKg: Double, carKg: Double, planeKg: Double, railKm: Double) {
        self.trainKg = trainKg
        self.carKg = carKg
        self.planeKg = planeKg
        self.railKm = railKm
    }
}

/// The CO2 estimate of a solution, computed on the device from the positions of its stations, as
/// Android's `Co2Estimator`: every vehicle leg is the great-circle distance between its stations
/// times the rail detour (the train's factor for the whole journey, buses included); car and plane
/// cover the great circle between first and last station times their own factor. Nil when a
/// station has no position or the trip is shorter than the table's minimum. Factors and distances
/// are `SharedData.Co2Estimate` (contracts/shared-data/v1/co2-estimate-v1.json); the cases are
/// contracts/shared-rules/v1/co2-estimate-v1.json.
public enum Co2Estimator {
    public static var minimumKm: Double { SharedData.Co2Estimate.minimumKm }

    /// Grams per passenger-km of a flight of `km`, between the two published distances and
    /// constant outside them.
    public static func planeGramsPerPkm(_ km: Double) -> Double {
        let table = SharedData.Co2Estimate.self
        if km <= table.planeShortKm { return table.planeGramsPerPkmShort }
        if km >= table.planeLongKm { return table.planeGramsPerPkmLong }
        let share = (km - table.planeShortKm) / (table.planeLongKm - table.planeShortKm)
        return table.planeGramsPerPkmShort + share * (table.planeGramsPerPkmLong - table.planeGramsPerPkmShort)
    }

    public static func estimate(_ legs: [Co2Leg]) -> Co2Estimate? {
        guard let first = legs.first, let last = legs.last else { return nil }
        let table = SharedData.Co2Estimate.self
        let railKm = legs.reduce(0.0) { $0 + greatCircleKm($1.from, $1.to) } * table.railDetour
        let directKm = greatCircleKm(first.from, last.to)
        guard railKm >= minimumKm, directKm >= minimumKm else { return nil }
        let flightKm = directKm * table.flightUplift
        return Co2Estimate(
            trainKg: railKm * table.trainGramsPerPkm / 1000.0,
            carKg: directKm * table.roadDetour * table.carGramsPerPkm / 1000.0,
            planeKg: flightKm * planeGramsPerPkm(flightKm) / 1000.0,
            railKm: railKm
        )
    }

    /// Legs with a missing station position make no estimate rather than a partial one.
    public static func estimate(points: [(Co2Point?, Co2Point?)]) -> Co2Estimate? {
        var legs: [Co2Leg] = []
        for (from, to) in points {
            guard let from, let to else { return nil }
            legs.append(Co2Leg(from: from, to: to))
        }
        return estimate(legs)
    }

    /// Haversine distance on the mean Earth radius.
    public static func greatCircleKm(_ from: Co2Point, _ to: Co2Point) -> Double {
        let lat1 = from.latitude * .pi / 180
        let lat2 = to.latitude * .pi / 180
        let dLat = lat2 - lat1
        let dLon = (to.longitude - from.longitude) * .pi / 180
        let a = pow(sin(dLat / 2), 2) + cos(lat1) * cos(lat2) * pow(sin(dLon / 2), 2)
        return 2 * SharedData.Co2Estimate.earthRadiusKm * asin(sqrt(min(max(a, 0), 1)))
    }

    /// «4,8» under 10 kg, «31» above: the precision an estimate deserves, with the language's
    /// decimal separator (Android's `displayKg`).
    public static func displayKg(_ kg: Double, locale: Locale) -> String {
        if kg < 10 {
            return String(format: "%.1f", locale: locale, kg)
        }
        // Kotlin's roundToInt: half up.
        return String(Int((kg + 0.5).rounded(.down)))
    }
}
