import Foundation

/// A catalogue station and its distance from one position.
public struct NearbyStation: Equatable, Sendable {
    public let station: Station
    public let distanceKilometres: Double
}

/// The nearest catalogue station to one position (EXPO_PORT_PLAN I7, F4 and R20), Android's
/// `StationCoordinates` and `NearestStationPicker`: only stations with finite coordinates count, the
/// nearest first, then by name in Italian order and by identifier; nothing farther than 50 km, so a
/// position abroad or far from any railway gets no station rather than an invented one. The
/// position is used for this computation only, on the phone, and kept nowhere.
public enum StationProximity {
    public static let defaultMaximumDistanceKilometres = 50.0
    /// The mean Earth radius Android uses.
    private static let earthRadiusKilometres = 6_371.0088

    /// Every station with coordinates, nearest first; empty for a position that is not finite.
    public static func nearby(_ stations: [Station], latitude: Double, longitude: Double) -> [NearbyStation] {
        guard latitude.isFinite, longitude.isFinite else { return [] }
        let italian = Locale(identifier: "it_IT")
        return stations.compactMap { station -> NearbyStation? in
            guard let stationLatitude = station.latitude, let stationLongitude = station.longitude,
                  stationLatitude.isFinite, stationLongitude.isFinite
            else { return nil }
            return NearbyStation(
                station: station,
                distanceKilometres: distanceKilometres(latitude, longitude, stationLatitude, stationLongitude)
            )
        }
        .sorted { left, right in
            if left.distanceKilometres != right.distanceKilometres {
                return left.distanceKilometres < right.distanceKilometres
            }
            // Android's Italian Collator at primary strength: case and accents do not count.
            let byName = left.station.name.compare(
                right.station.name,
                options: [.caseInsensitive, .diacriticInsensitive],
                range: nil,
                locale: italian
            )
            if byName != .orderedSame { return byName == .orderedAscending }
            return left.station.id.utf16.lexicographicallyPrecedes(right.station.id.utf16)
        }
    }

    /// The nearest station within `maximumDistanceKilometres`, or nil.
    public static func nearest(
        _ stations: [Station],
        latitude: Double,
        longitude: Double,
        maximumDistanceKilometres: Double = defaultMaximumDistanceKilometres
    ) -> NearbyStation? {
        guard let first = nearby(stations, latitude: latitude, longitude: longitude).first,
              first.distanceKilometres <= maximumDistanceKilometres
        else { return nil }
        return first
    }

    static func distanceKilometres(_ latitudeA: Double, _ longitudeA: Double, _ latitudeB: Double, _ longitudeB: Double) -> Double {
        let radians = Double.pi / 180
        let latitudeDelta = (latitudeB - latitudeA) * radians
        let longitudeDelta = (longitudeB - longitudeA) * radians
        let a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2)
            + cos(latitudeA * radians) * cos(latitudeB * radians) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * earthRadiusKilometres * atan2(a.squareRoot(), (1 - a).squareRoot())
    }
}
