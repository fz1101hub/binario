import Foundation

public enum WearSnapshotError: Error, Equatable, Sendable {
    case unsupportedSchema
    case invalidProducer
    case invalidTimestamp
    case invalidTimeToLive
    case invalidProvenance
    case invalidRoute
    case invalidNotice
    case tooManyServices
    case invalidService
    case incoherentUnavailableState
    case payloadTooLarge
    case unsupportedSession
    case publicationFailed
}

public struct WearServiceV1: Equatable, Sendable {
    public let number: String
    public let operatorName: String
    public let category: String
    public let destination: String
    public let scheduled: String
    public let expected: String
    public let status: String
    public let platform: String
    public let delayMinutes: Int

    public init(
        number: String,
        operatorName: String,
        category: String,
        destination: String,
        scheduled: String,
        expected: String,
        status: String,
        platform: String = "",
        delayMinutes: Int
    ) {
        self.number = number
        self.operatorName = operatorName
        self.category = category
        self.destination = destination
        self.scheduled = scheduled
        self.expected = expected
        self.status = status
        self.platform = platform
        self.delayMinutes = delayMinutes
    }
}

public struct WearSnapshotV1: Encodable, Equatable, Sendable {
    public static let schemaVersion = 1
    public static let maximumEncodedBytes = 8_192
    public static let maximumServices = 2
    public static let maximumRouteLabelCharacters = 96
    public static let maximumTTLMillis: Int64 = 365 * 24 * 60 * 60 * 1_000
    public static let maximumFutureSkewMillis: Int64 = 60_000

    public let schemaVersion: Int
    public let generatedAtEpochMillis: Int64
    public let expiresAtEpochMillis: Int64
    public let ttlMillis: Int64
    public let provenanceProducer: DataProducer
    public let provenanceDataNature: DataNature
    public let provenanceSourceLabel: String
    public let provenanceObservedAtEpochMillis: Int64
    public let operationalState: OperationalState
    public let freshness: DataFreshness
    public let confidence: DataConfidence
    public let routeId: String
    public let routeLabel: String
    public let direction: JourneyDirection
    public let notice: String
    public let services: [WearServiceV1]

    public init(
        schemaVersion: Int = WearSnapshotV1.schemaVersion,
        generatedAtEpochMillis: Int64,
        expiresAtEpochMillis: Int64,
        ttlMillis: Int64,
        provenanceProducer: DataProducer,
        provenanceDataNature: DataNature,
        provenanceSourceLabel: String,
        provenanceObservedAtEpochMillis: Int64,
        operationalState: OperationalState,
        freshness: DataFreshness,
        confidence: DataConfidence,
        routeId: String,
        routeLabel: String,
        direction: JourneyDirection,
        notice: String,
        services: [WearServiceV1]
    ) {
        self.schemaVersion = schemaVersion
        self.generatedAtEpochMillis = generatedAtEpochMillis
        self.expiresAtEpochMillis = expiresAtEpochMillis
        self.ttlMillis = ttlMillis
        self.provenanceProducer = provenanceProducer
        self.provenanceDataNature = provenanceDataNature
        self.provenanceSourceLabel = provenanceSourceLabel
        self.provenanceObservedAtEpochMillis = provenanceObservedAtEpochMillis
        self.operationalState = operationalState
        self.freshness = freshness
        self.confidence = confidence
        self.routeId = routeId
        self.routeLabel = routeLabel
        self.direction = direction
        self.notice = notice
        self.services = services
    }

    public static func unavailable(
        routeId: String,
        routeLabel: String,
        direction: JourneyDirection,
        generatedAtEpochMillis: Int64,
        sourceLabel: String,
        notice: String,
        ttlMillis: Int64 = 15 * 60 * 1_000
    ) -> WearSnapshotV1 {
        WearSnapshotV1(
            generatedAtEpochMillis: generatedAtEpochMillis,
            expiresAtEpochMillis: generatedAtEpochMillis + ttlMillis,
            ttlMillis: ttlMillis,
            provenanceProducer: .iosApp,
            provenanceDataNature: .unavailable,
            provenanceSourceLabel: sourceLabel,
            provenanceObservedAtEpochMillis: generatedAtEpochMillis,
            operationalState: .unavailable,
            freshness: .unavailable,
            confidence: .unknown,
            routeId: routeId,
            routeLabel: routeLabel,
            direction: direction,
            notice: notice,
            services: []
        )
    }

    public func validate(at nowEpochMillis: Int64) throws {
        guard schemaVersion == Self.schemaVersion else { throw WearSnapshotError.unsupportedSchema }
        guard provenanceProducer == .iosApp else { throw WearSnapshotError.invalidProducer }
        guard generatedAtEpochMillis >= 0,
              provenanceObservedAtEpochMillis >= 0,
              generatedAtEpochMillis <= nowEpochMillis + Self.maximumFutureSkewMillis,
              provenanceObservedAtEpochMillis <= generatedAtEpochMillis + Self.maximumFutureSkewMillis,
              expiresAtEpochMillis > nowEpochMillis
        else { throw WearSnapshotError.invalidTimestamp }
        guard (1...Self.maximumTTLMillis).contains(ttlMillis),
              expiresAtEpochMillis - generatedAtEpochMillis == ttlMillis
        else { throw WearSnapshotError.invalidTimeToLive }
        guard Self.isRequired(provenanceSourceLabel, maximum: 96) else { throw WearSnapshotError.invalidProvenance }
        guard Self.isRequired(routeId, maximum: 64),
              Self.isRequired(routeLabel, maximum: Self.maximumRouteLabelCharacters)
        else {
            throw WearSnapshotError.invalidRoute
        }
        guard Self.isOptional(notice, maximum: 180) else { throw WearSnapshotError.invalidNotice }
        guard services.count <= Self.maximumServices else { throw WearSnapshotError.tooManyServices }
        if provenanceDataNature == .unavailable || operationalState == .unavailable {
            guard provenanceDataNature == .unavailable,
                  operationalState == .unavailable,
                  freshness == .unavailable,
                  services.isEmpty
            else { throw WearSnapshotError.incoherentUnavailableState }
        }
        guard services.allSatisfy(Self.isValid(service:)) else { throw WearSnapshotError.invalidService }
    }

    private static func isValid(service: WearServiceV1) -> Bool {
        isRequired(service.number, maximum: 24) &&
            isRequired(service.operatorName, maximum: 64) &&
            isRequired(service.category, maximum: 64) &&
            isRequired(service.destination, maximum: 96) &&
            isRequired(service.scheduled, maximum: 16) &&
            isRequired(service.expected, maximum: 16) &&
            isRequired(service.status, maximum: 64) &&
            isOptional(service.platform, maximum: 16) &&
            (-720...1_440).contains(service.delayMinutes)
    }

    private static func isRequired(_ value: String, maximum: Int) -> Bool {
        !value.isEmpty && value.count <= maximum && value == normalized(value)
    }

    private static func isOptional(_ value: String, maximum: Int) -> Bool {
        value.count <= maximum && (value.isEmpty || value == normalized(value))
    }

    private static func normalized(_ value: String) -> String {
        value.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: DynamicCodingKey.self)
        try container.encode(schemaVersion, forKey: .init("schemaVersion"))
        try container.encode(generatedAtEpochMillis, forKey: .init("generatedAtEpochMillis"))
        try container.encode(expiresAtEpochMillis, forKey: .init("expiresAtEpochMillis"))
        try container.encode(ttlMillis, forKey: .init("ttlMillis"))
        try container.encode(provenanceProducer.rawValue, forKey: .init("provenanceProducer"))
        try container.encode(provenanceDataNature.rawValue, forKey: .init("provenanceDataNature"))
        try container.encode(provenanceSourceLabel, forKey: .init("provenanceSourceLabel"))
        try container.encode(provenanceObservedAtEpochMillis, forKey: .init("provenanceObservedAtEpochMillis"))
        try container.encode(operationalState.rawValue, forKey: .init("operationalState"))
        try container.encode(freshness.rawValue, forKey: .init("freshness"))
        try container.encode(confidence.rawValue, forKey: .init("confidence"))
        try container.encode(routeId, forKey: .init("routeId"))
        try container.encode(routeLabel, forKey: .init("routeLabel"))
        try container.encode(direction.rawValue, forKey: .init("direction"))
        try container.encode(notice, forKey: .init("notice"))
        try container.encode(services.count, forKey: .init("serviceCount"))

        for (index, service) in services.enumerated() {
            try container.encode(service.number, forKey: .init("service\(index)Number"))
            try container.encode(service.operatorName, forKey: .init("service\(index)Operator"))
            try container.encode(service.category, forKey: .init("service\(index)Category"))
            try container.encode(service.destination, forKey: .init("service\(index)Destination"))
            try container.encode(service.scheduled, forKey: .init("service\(index)Scheduled"))
            try container.encode(service.expected, forKey: .init("service\(index)Expected"))
            try container.encode(service.status, forKey: .init("service\(index)Status"))
            try container.encode(service.platform, forKey: .init("service\(index)Platform"))
            try container.encode(service.delayMinutes, forKey: .init("service\(index)DelayMinutes"))
        }
    }
}

public struct WearSnapshotEncoder: Sendable {
    public init() {}

    public func encode(_ snapshot: WearSnapshotV1, nowEpochMillis: Int64) throws -> Data {
        try snapshot.validate(at: nowEpochMillis)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(snapshot)
        guard data.count <= WearSnapshotV1.maximumEncodedBytes else { throw WearSnapshotError.payloadTooLarge }
        return data
    }
}

public enum WatchRouteLabelFormatter {
    public static let maximumCharacters = WearSnapshotV1.maximumRouteLabelCharacters
    private static let separator = " → "
    private static let identifierSeparator = " · "

    public static func format(origin: StationIdentity, destination: StationIdentity) -> String {
        format(
            originName: origin.name,
            destinationName: destination.name,
            routeId: SavedRoute.routeIdentifier(origin: origin, destination: destination)
        )
    }

    public static func format(originName: String, destinationName: String, routeId: String) -> String {
        let normalizedOrigin = normalize(originName)
        let normalizedDestination = normalize(destinationName)
        let normalizedRouteId = normalize(routeId)
        let suffix = identifierSeparator + normalizedRouteId
        let availableNames = maximumCharacters - suffix.count
        guard availableNames > separator.count + 1 else {
            return String((normalizedRouteId.isEmpty ? "route" : normalizedRouteId).prefix(maximumCharacters))
        }

        let fullNames = normalizedOrigin + separator + normalizedDestination
        let names: String
        if fullNames.count <= availableNames {
            names = fullNames
        } else {
            let contentBudget = availableNames - separator.count
            let originBudget = contentBudget / 2
            let destinationBudget = contentBudget - originBudget
            names = abbreviated(normalizedOrigin, maximum: originBudget)
                + separator
                + abbreviated(normalizedDestination, maximum: destinationBudget)
        }
        return names + suffix
    }

    private static func abbreviated(_ value: String, maximum: Int) -> String {
        guard value.count > maximum else { return value }
        guard maximum > 1 else { return "…" }
        return String(value.prefix(maximum - 1)) + "…"
    }

    private static func normalize(_ value: String) -> String {
        value.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }
}

public enum WatchSessionActivationState: Equatable, Sendable {
    case notActivated
    case inactive
    case activated
    case unsupported
    case failed
}

public enum WatchSyncOperation: Equatable, Sendable {
    case snapshot(Data)
    case clear
}

public enum WatchSyncStatus: Equatable, Sendable {
    case disabled
    case activating
    case pending
    case contextUpdated
    case cleared
    case unsupported
    /// The iPhone has no paired Apple Watch with the Binario app: nothing can be delivered,
    /// which is a neutral state, not a failure.
    case notPaired
    case failed
}

public enum WatchSyncDirective: Equatable, Sendable {
    case none
    case activate
    case publish(WatchSyncOperation)
}

public struct WatchSyncStateMachine: Sendable {
    public private(set) var activationState: WatchSessionActivationState = .notActivated
    public private(set) var latestPendingOperation: WatchSyncOperation?
    private var activationRequested = false

    public init() {}

    public mutating func bootstrap(isSupported: Bool) -> WatchSyncDirective {
        guard isSupported else {
            activationState = .unsupported
            latestPendingOperation = nil
            return .none
        }
        guard !activationRequested else { return .none }
        activationRequested = true
        activationState = .inactive
        return .activate
    }

    public mutating func enqueue(_ operation: WatchSyncOperation) -> WatchSyncDirective {
        guard activationState != .unsupported else { return .none }
        if activationState == .activated {
            return .publish(operation)
        }
        latestPendingOperation = operation
        return .none
    }

    public mutating func activationDidComplete(
        _ state: WatchSessionActivationState
    ) -> WatchSyncDirective {
        activationState = state
        guard state == .activated, let pending = latestPendingOperation else { return .none }
        latestPendingOperation = nil
        return .publish(pending)
    }

    public mutating func publicationFailed(_ operation: WatchSyncOperation) {
        latestPendingOperation = operation
    }
}

@MainActor
public protocol WatchSnapshotSession: AnyObject {
    var isSupported: Bool { get }
    var activationState: WatchSessionActivationState { get }
    /// Meaningful only once the session is activated, like WCSession.isPaired.
    var isPaired: Bool { get }
    var isWatchAppInstalled: Bool { get }
    var activationHandler: (@MainActor (WatchSessionActivationState) -> Void)? { get set }
    func activate()
    func updateApplicationContext(_ context: [String: Data]) throws
}

@MainActor
public final class WatchSnapshotPublisher {
    public static let contextKey = "binario_snapshot_json"
    public static let clearContextKey = "binario_snapshot_clear"

    public var statusDidChange: (@MainActor (WatchSyncStatus) -> Void)?
    public private(set) var status: WatchSyncStatus = .disabled {
        didSet { statusDidChange?(status) }
    }

    private let session: any WatchSnapshotSession
    private let encoder: WearSnapshotEncoder
    private var stateMachine = WatchSyncStateMachine()

    public init(session: any WatchSnapshotSession, encoder: WearSnapshotEncoder = WearSnapshotEncoder()) {
        self.session = session
        self.encoder = encoder
        self.session.activationHandler = { [weak self] state in
            self?.activationDidComplete(state)
        }
    }

    public func bootstrap() {
        execute(stateMachine.bootstrap(isSupported: session.isSupported))
        if stateMachine.activationState == .unsupported {
            status = .unsupported
        } else if session.activationState == .activated {
            activationDidComplete(.activated)
        } else {
            status = .activating
        }
    }

    @discardableResult
    public func publish(_ snapshot: WearSnapshotV1, nowEpochMillis: Int64) throws -> Data {
        let data = try encoder.encode(snapshot, nowEpochMillis: nowEpochMillis)
        try queue(.snapshot(data))
        return data
    }

    public func clear() throws {
        try queue(.clear)
    }

    private func queue(_ operation: WatchSyncOperation) throws {
        guard session.isSupported else {
            _ = stateMachine.bootstrap(isSupported: false)
            status = .unsupported
            throw WearSnapshotError.unsupportedSession
        }
        status = .pending
        try executeThrowing(stateMachine.enqueue(operation))
    }

    private func activationDidComplete(_ state: WatchSessionActivationState) {
        do {
            try executeThrowing(stateMachine.activationDidComplete(state))
            switch state {
            case .activated:
                if stateMachine.latestPendingOperation == nil,
                   status != .contextUpdated,
                   status != .cleared {
                    status = .activating
                }
            case .unsupported:
                status = .unsupported
            case .failed:
                status = .failed
            case .inactive, .notActivated:
                status = .activating
            }
        } catch {
            status = .failed
        }
    }

    private func execute(_ directive: WatchSyncDirective) {
        switch directive {
        case .activate:
            session.activate()
        case .none:
            break
        case .publish:
            preconditionFailure("Publishing directive requires throwing execution")
        }
    }

    private func executeThrowing(_ directive: WatchSyncDirective) throws {
        switch directive {
        case .none:
            break
        case .activate:
            session.activate()
        case let .publish(operation):
            // Publish directives are only issued once activated, where the pairing state is
            // valid. Without a paired Watch carrying the app, updateApplicationContext throws
            // on every launch: keep the operation for a later activation and report the
            // neutral state instead of a failure.
            guard session.isPaired, session.isWatchAppInstalled else {
                stateMachine.publicationFailed(operation)
                status = .notPaired
                return
            }
            do {
                switch operation {
                case let .snapshot(data):
                    try session.updateApplicationContext([Self.contextKey: data])
                    status = .contextUpdated
                case .clear:
                    try session.updateApplicationContext([Self.clearContextKey: Data()])
                    status = .cleared
                }
            } catch {
                stateMachine.publicationFailed(operation)
                status = .failed
                throw WearSnapshotError.publicationFailed
            }
        }
    }
}

private struct DynamicCodingKey: CodingKey, Hashable {
    let stringValue: String
    let intValue: Int? = nil

    init(_ value: String) { stringValue = value }
    init?(stringValue: String) { self.init(stringValue) }
    init?(intValue: Int) { return nil }
}
