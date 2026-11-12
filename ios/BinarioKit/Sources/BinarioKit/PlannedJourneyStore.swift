import Foundation

/// What the stored Agenda holds when it is read.
public struct PlannedJourneyAgendaState: Equatable, Sendable {
    /// Every readable journey, in the stored order.
    public let journeys: [PlannedJourney]
    /// The stored value is not fully readable: every write is refused, so a save cannot overwrite
    /// the journeys this version could not read (Android's read-only rule, EXPO_PORT_PLAN R11).
    public let isReadOnly: Bool
    /// Stored records left out of `journeys` (0 when the whole value is unreadable or valid).
    public let unreadableRecords: Int

    public init(journeys: [PlannedJourney], isReadOnly: Bool = false, unreadableRecords: Int = 0) {
        self.journeys = journeys
        self.isReadOnly = isReadOnly
        self.unreadableRecords = unreadableRecords
    }

    public static let empty = PlannedJourneyAgendaState(journeys: [])
}

/// Why a change to the Agenda was not saved; the stored value is untouched in every case.
public enum PlannedJourneyRefusal: Error, Equatable, Sendable {
    /// The stored Agenda is not fully readable (`PlannedJourneyAgendaState.isReadOnly`).
    case readOnly
    /// 40 journeys: one is deleted before another is saved; none is replaced.
    case full
    /// The same stations, date and time are already in the Agenda.
    case duplicate
    case notFound
    case invalid(PlannedJourneyValidationError)
    case storageFailure
}

/// The Agenda in the app's `UserDefaults`, on this iPhone only: Android's transfer document (the
/// format Android stores too) under `storageKey`. Nothing is synchronised or sent; Apple system
/// backups may include it.
///
/// Every change reads the stored value again and writes the whole document, so a change can never
/// start from a stale or partial list. The store is not `Sendable`: the app keeps one, used on
/// the main actor only, so two saves cannot interleave and the last free place goes to one of them.
public final class PlannedJourneyStore {
    public static let storageKey = "it.binario.ios.planned-journeys.v1"

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The stored Agenda. Nothing stored is an empty, writable Agenda; a value this version cannot
    /// read whole keeps its readable journeys and becomes read-only.
    public func load() -> PlannedJourneyAgendaState {
        guard let data = defaults.data(forKey: Self.storageKey) else {
            // A value of another type was not written by this app: leave it alone.
            return defaults.object(forKey: Self.storageKey) == nil ? .empty : PlannedJourneyAgendaState(journeys: [], isReadOnly: true)
        }
        guard !data.isEmpty else { return .empty }
        if let collection = PlannedJourneyTransfer.decode(data) {
            return PlannedJourneyAgendaState(journeys: collection.journeys)
        }
        let recovered = PlannedJourneyTransfer.recover(data)
        return PlannedJourneyAgendaState(
            journeys: recovered?.journeys ?? [],
            isReadOnly: true,
            unreadableRecords: recovered?.unreadableRecords ?? 0
        )
    }

    /// A new journey, after the others. Refused when the Agenda is read-only, already holds the
    /// same journey, or is full.
    public func add(_ journey: PlannedJourney) -> Result<PlannedJourneyAgendaState, PlannedJourneyRefusal> {
        let current = load()
        guard !current.isReadOnly else { return .failure(.readOnly) }
        guard !current.journeys.contains(where: { $0.fingerprint == journey.fingerprint }) else { return .failure(.duplicate) }
        guard current.journeys.count < PlannedJourneySchema.maximumJourneys else { return .failure(.full) }
        guard !current.journeys.contains(where: { $0.id == journey.id }) else {
            return .failure(.invalid(.duplicateIdentifier))
        }
        return write(current.journeys + [journey])
    }

    /// An edited journey in place of the one with its identifier.
    public func replace(_ journey: PlannedJourney) -> Result<PlannedJourneyAgendaState, PlannedJourneyRefusal> {
        let current = load()
        guard !current.isReadOnly else { return .failure(.readOnly) }
        guard let index = current.journeys.firstIndex(where: { $0.id == journey.id }) else { return .failure(.notFound) }
        let others = current.journeys.enumerated().filter { $0.offset != index }.map(\.element)
        guard !others.contains(where: { $0.fingerprint == journey.fingerprint }) else { return .failure(.duplicate) }
        var journeys = current.journeys
        journeys[index] = journey
        return write(journeys)
    }

    public func remove(id: String) -> Result<PlannedJourneyAgendaState, PlannedJourneyRefusal> {
        let current = load()
        guard !current.isReadOnly else { return .failure(.readOnly) }
        guard current.journeys.contains(where: { $0.id == id }) else { return .failure(.notFound) }
        return write(current.journeys.filter { $0.id != id })
    }

    /// «Completa»: the journey goes to the archive.
    public func complete(id: String, at date: Date) -> Result<PlannedJourneyAgendaState, PlannedJourneyRefusal> {
        transform(id: id) { try $0.completing(at: date) }
    }

    /// «Completa» on one departure of a repeating journey: that date is marked done; the journey
    /// goes to the archive once no departure is left.
    public func completeOccurrence(id: String, on date: PlannedLocalDate) -> Result<PlannedJourneyAgendaState, PlannedJourneyRefusal> {
        transform(id: id) { try $0.completingOccurrence(on: date) }
    }

    /// One checklist line ticked or not.
    public func setChecklistItem(_ itemID: String, completed: Bool, journeyID: String) -> Result<PlannedJourneyAgendaState, PlannedJourneyRefusal> {
        transform(id: journeyID) { try $0.settingChecklistItem(id: itemID, completed: completed) }
    }

    /// The whole Agenda written at once (an import that has already applied its own rules);
    /// refused like any change when the stored value is read-only.
    func replaceAll(_ journeys: [PlannedJourney]) -> Result<PlannedJourneyAgendaState, PlannedJourneyRefusal> {
        guard !load().isReadOnly else { return .failure(.readOnly) }
        return write(journeys)
    }

    private func transform(
        id: String,
        _ change: (PlannedJourney) throws -> PlannedJourney
    ) -> Result<PlannedJourneyAgendaState, PlannedJourneyRefusal> {
        let current = load()
        guard !current.isReadOnly else { return .failure(.readOnly) }
        guard let index = current.journeys.firstIndex(where: { $0.id == id }) else { return .failure(.notFound) }
        var journeys = current.journeys
        do {
            journeys[index] = try change(journeys[index])
        } catch let error as PlannedJourneyValidationError {
            return .failure(.invalid(error))
        } catch {
            return .failure(.storageFailure)
        }
        return write(journeys)
    }

    private func write(_ journeys: [PlannedJourney]) -> Result<PlannedJourneyAgendaState, PlannedJourneyRefusal> {
        let collection: PlannedJourneyCollection
        do {
            collection = try PlannedJourneyCollection(validating: journeys)
        } catch let error as PlannedJourneyValidationError {
            return .failure(.invalid(error))
        } catch {
            return .failure(.storageFailure)
        }
        guard let data = try? PlannedJourneyTransfer.encode(collection) else { return .failure(.storageFailure) }
        defaults.set(data, forKey: Self.storageKey)
        return .success(PlannedJourneyAgendaState(journeys: collection.journeys))
    }
}
