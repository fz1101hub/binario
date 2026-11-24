import Foundation

/// What a stop is on the route map (Android's `RouteStopRole`). Several can apply to one stop: an
/// origin can also be the last stop a source confirmed.
public enum RouteMapRole: String, CaseIterable, Hashable, Sendable {
    case origin
    case destination
    case lastConfirmed
    case next
    case skipped

    /// The role that decides the marker: live progress first, then a skipped stop, then the ends.
    public static let precedence: [RouteMapRole] = [.next, .lastConfirmed, .skipped, .origin, .destination]
}

/// One stop of a train as the map is given it: the published name, the times as shown and what a
/// source confirmed (Android's `TrainStop` fields the map reads).
public struct RouteMapEntry: Equatable, Sendable {
    public let name: String
    public let scheduled: String
    public let expected: String
    /// A source published an actual time there (Android's `completed`).
    public let passed: Bool
    public let skipped: Bool
    /// Whether the stop's times come from a source that verified them; nil inherits the service's.
    public let verified: Bool?

    public init(name: String, scheduled: String, expected: String? = nil, passed: Bool = false, skipped: Bool = false, verified: Bool? = nil) {
        self.name = name
        self.scheduled = scheduled
        self.expected = expected ?? scheduled
        self.passed = passed
        self.skipped = skipped
        self.verified = verified
    }
}

/// A stop on the map: its place in the run, its times and its catalogue coordinates when known.
public struct RouteMapStop: Identifiable, Equatable, Sendable {
    public let id: Int
    public let name: String
    public let scheduled: String
    /// Only when the source verified it and it differs from the timetable.
    public let expected: String?
    /// Catalogue coordinates; nil when the stop matches no single station with coordinates.
    public let point: GeoPoint?
    public let roles: Set<RouteMapRole>

    public var primaryRole: RouteMapRole? { RouteMapRole.precedence.first { roles.contains($0) } }
}

/// The last stop a source confirmed and the first later stop still to be served (Android's `RouteProgress`).
public struct RouteMapProgress: Equatable, Sendable {
    public let lastConfirmed: Int?
    public let next: Int?

    public init(lastConfirmed: Int?, next: Int?) {
        self.lastConfirmed = lastConfirmed
        self.next = next
    }
}

public struct RouteMapModel: Equatable, Sendable {
    /// Every stop in route order, mapped or not: the text under the map lists them all.
    public let stops: [RouteMapStop]
    /// The schematic line: mapped stops the train calls at, in order. It is not the track.
    public let line: [GeoPoint]

    public var mappedStops: [RouteMapStop] { stops.filter { $0.point != nil } }
    public var unmappedStops: [RouteMapStop] { stops.filter { $0.point == nil } }
}

/// Android's `RouteMapPlanner`: Binario has no train positions, so the map highlights only the last
/// stop whose passage a source confirmed and the next one; without such a stop nothing is.
public enum RouteMapPlanner {
    private static let placeholderTimes: Set<String> = ["", "—", "-", "--", "--:--"]

    public static func progress(_ entries: [RouteMapEntry]) -> RouteMapProgress {
        guard let last = entries.indices.last(where: { entries[$0].passed && !entries[$0].skipped }) else {
            return RouteMapProgress(lastConfirmed: nil, next: nil)
        }
        let next = (last + 1..<entries.count).first { !entries[$0].passed && !entries[$0].skipped }
        return RouteMapProgress(lastConfirmed: last, next: next)
    }

    /// - Parameters:
    ///   - serviceVerified: what a stop without its own flag inherits.
    ///   - locate: the catalogue coordinates of a published name; nil without a single match.
    public static func build(_ entries: [RouteMapEntry], serviceVerified: Bool, locate: (String) -> GeoPoint?) -> RouteMapModel {
        let progress = progress(entries)
        var located: [String: GeoPoint?] = [:]
        var stops: [RouteMapStop] = []
        for (index, entry) in entries.enumerated() {
            var roles = Set<RouteMapRole>()
            if index == 0 { roles.insert(.origin) }
            if index == entries.count - 1, index > 0 { roles.insert(.destination) }
            if index == progress.lastConfirmed { roles.insert(.lastConfirmed) }
            if index == progress.next { roles.insert(.next) }
            if entry.skipped { roles.insert(.skipped) }
            let verified = entry.verified ?? serviceVerified
            let expected = entry.expected.trimmingCharacters(in: .whitespaces)
            let point: GeoPoint?
            if let known = located[entry.name] {
                point = known
            } else {
                let found = locate(entry.name).flatMap { $0.isValid ? $0 : nil }
                located[entry.name] = .some(found)
                point = found
            }
            stops.append(RouteMapStop(
                id: index,
                name: entry.name,
                scheduled: entry.scheduled,
                expected: verified && entry.expected != entry.scheduled && !placeholderTimes.contains(expected) ? entry.expected : nil,
                point: point,
                roles: roles
            ))
        }
        var line: [GeoPoint] = []
        for stop in stops where !stop.roles.contains(.skipped) {
            guard let point = stop.point, line.last != point else { continue }
            line.append(point)
        }
        return RouteMapModel(stops: stops, line: line.count >= 2 ? line : [])
    }
}
