import Foundation

/// Which default radius a train gets (Android's `GpsAlarmTrainClass`): high-speed and long-distance
/// trains cover several kilometres a minute, so a radius that suits a regional train would ring when
/// the train is practically already in the station.
public enum GpsAlarmTrainClass: String, Codable, CaseIterable, Sendable {
    case longDistance = "LONG_DISTANCE"
    case regional = "REGIONAL"
}

/// Whether the GPS option can be offered for a stop (Android's `GpsAlarmAvailability`).
public enum GpsAlarmAvailability: Equatable, Sendable {
    case available
    case noCoordinates
    case tooEarly
}

/// Android's GPS stop alarm («Avvisami anche in base alla posizione») on the iPhone: a circle around
/// the stop, from the bundled catalogue's coordinates and the radius chosen, that iOS watches to show
/// one local notification on entry. The app never receives a position: nothing here holds one.
public struct GpsStopAlarm: Codable, Equatable, Sendable {
    public let trainNumber: String
    public let destination: String
    public let station: String
    /// "8:30", as the stop alarm shows it.
    public let expectedTime: String
    public let expectedAt: Date
    /// The stop's coordinates in the bundled catalogue.
    public let latitude: Double
    public let longitude: Double
    public let radiusMeters: Int
    /// Two hours after the expected arrival, at most eight hours after it was set.
    public let expiresAt: Date

    public init(
        trainNumber: String, destination: String, station: String, expectedTime: String, expectedAt: Date,
        latitude: Double, longitude: Double, radiusMeters: Int, expiresAt: Date
    ) {
        self.trainNumber = trainNumber
        self.destination = destination
        self.station = station
        self.expectedTime = expectedTime
        self.expectedAt = expectedAt
        self.latitude = latitude
        self.longitude = longitude
        self.radiusMeters = radiusMeters
        self.expiresAt = expiresAt
    }

    public func isFor(trainNumber: String, station: String) -> Bool {
        StopAlarmPlanner.key(self.trainNumber, self.station) == StopAlarmPlanner.key(trainNumber, station)
    }
}

/// Android's `GpsArrivalAlarmPolicy`, the rules that do not need a position: the radius choices and
/// defaults (contracts/shared-data/v1/trip-alerts-v1.json), the train class, when the option is
/// offered and when the alarm ends.
public enum GpsStopAlarmPolicy {
    public static let radiusOptionsMeters = SharedData.TripAlerts.gpsAlarmRadiusOptionsMeters
    public static let regionalDefaultRadiusMeters = SharedData.TripAlerts.gpsAlarmRegionalDefaultRadiusMeters
    public static let longDistanceDefaultRadiusMeters = SharedData.TripAlerts.gpsAlarmLongDistanceDefaultRadiusMeters
    static let afterArrival = TimeInterval(SharedData.TripAlerts.positionSessionAfterArrivalMinutes * 60)
    static let maximumDuration = TimeInterval(SharedData.TripAlerts.positionSessionMaximumMinutes * 60)

    /// The eight-hour cap in whole hours, for the «too early» explanation.
    public static var maximumDurationHours: Int { SharedData.TripAlerts.positionSessionMaximumMinutes / 60 }

    /// Android's `trainClass` on the iPhone's train groups: high-speed, Intercity and night, Italo
    /// and international trains are long-distance; regional and suburban trains, replacement buses
    /// and trains of no known group are regional.
    public static func trainClass(_ group: TrainTypeGroup?) -> GpsAlarmTrainClass {
        switch group {
        case .highSpeed, .intercityNight, .italo, .international: .longDistance
        case .regionalSuburban, .replacementBus, nil: .regional
        }
    }

    public static func defaultRadiusMeters(_ trainClass: GpsAlarmTrainClass) -> Int {
        switch trainClass {
        case .longDistance: longDistanceDefaultRadiusMeters
        case .regional: regionalDefaultRadiusMeters
        }
    }

    /// The remembered choice for the class when it is still a valid option, otherwise the class default.
    public static func initialRadiusMeters(_ trainClass: GpsAlarmTrainClass, remembered: Int?) -> Int {
        if let remembered, isRadiusOption(remembered) { return remembered }
        return defaultRadiusMeters(trainClass)
    }

    public static func isRadiusOption(_ radiusMeters: Int) -> Bool {
        radiusOptionsMeters.contains(radiusMeters)
    }

    /// The option needs the stop's coordinates and an arrival the eight-hour cap still covers.
    public static func availability(now: Date, arrival: Date, hasCoordinates: Bool) -> GpsAlarmAvailability {
        if !hasCoordinates { return .noCoordinates }
        if arrival.timeIntervalSince(now) > maximumDuration { return .tooEarly }
        return .available
    }

    /// Two hours after the expected arrival, capped at eight hours from the start.
    public static func expiresAt(startedAt: Date, arrival: Date) -> Date {
        min(arrival.addingTimeInterval(afterArrival), startedAt.addingTimeInterval(maximumDuration))
    }

    public static func isExpired(_ alarm: GpsStopAlarm, now: Date) -> Bool {
        now >= alarm.expiresAt
    }

    /// The alarm for a stop alarm just set, or nil when the radius is not an option, the coordinates
    /// are not valid or the stop is not available any more.
    public static func alarm(for plan: StopAlarmPlan, latitude: Double, longitude: Double, radiusMeters: Int, now: Date) -> GpsStopAlarm? {
        guard isRadiusOption(radiusMeters), (-90...90).contains(latitude), (-180...180).contains(longitude),
              latitude != 0 || longitude != 0,
              availability(now: now, arrival: plan.expectedAt, hasCoordinates: true) == .available else { return nil }
        return GpsStopAlarm(
            trainNumber: plan.trainNumber, destination: plan.destination, station: plan.station, expectedTime: plan.expectedTime,
            expectedAt: plan.expectedAt, latitude: latitude, longitude: longitude, radiusMeters: radiusMeters,
            expiresAt: expiresAt(startedAt: now, arrival: plan.expectedAt)
        )
    }
}
