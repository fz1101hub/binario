import Foundation

/// A reusable journey of the Agenda (Android's `PlannedJourneyTemplate`): one leg between two
/// catalogue stations, a departure time, a reminder lead and checklist lines, never a date (it is
/// always chosen anew). On this iPhone only, never in the Agenda's JSON or ICS files.
public struct PlannedJourneyTemplate: Equatable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let origin: PlannedJourneyStation
    public let destination: PlannedJourneyStation
    public let time: PlannedLocalTime
    public let reminderMinutes: Int?
    public let checklist: [String]

    /// Nil unless it would make a valid journey (Android's `validated`): a trimmed name of 1 to 48
    /// characters without control characters, two distinct stations, one of Android's leads and
    /// checklist lines that pass the Agenda's checks.
    public init?(
        id: String,
        name: String,
        origin: PlannedJourneyStation,
        destination: PlannedJourneyStation,
        time: PlannedLocalTime,
        reminderMinutes: Int? = nil,
        checklist: [String] = []
    ) {
        let trimmed = PlannedJourneyText.trimmed(name)
        guard !trimmed.isEmpty, PlannedJourneyText.length(trimmed) <= PlannedJourneyTemplates.maximumNameLength,
              !PlannedJourneyText.hasControl(trimmed),
              let probeDate = PlannedLocalDate(year: 2030, month: 1, day: 7),
              let departure = try? PlannedJourneyDateTime(date: probeDate, time: time),
              let segment = try? PlannedJourneySegment(origin: origin, destination: destination, departure: departure),
              let items = try? checklist.enumerated().map({ (entry: (offset: Int, element: String)) throws -> PlannedJourneyChecklistItem in
                  try PlannedJourneyChecklistItem(id: "check-\(entry.offset + 1)", label: entry.element)
              }),
              (try? PlannedJourney(
                  id: id, segment: segment, reminderMinutes: reminderMinutes, checklist: items,
                  createdAt: Date(timeIntervalSince1970: 0)
              )) != nil
        else { return nil }
        self.id = id
        self.name = trimmed
        self.origin = origin
        self.destination = destination
        self.time = time
        self.reminderMinutes = reminderMinutes
        self.checklist = checklist
    }
}

/// Android's template rules and document (`PlannedJourneyTemplates`): at most twelve, newest
/// first, a template with the same name replaced; a stored value that cannot be read is no template.
public enum PlannedJourneyTemplates {
    public static let maximumTemplates = 12
    public static let maximumNameLength = 48
    static let kind = "binario_planned_journey_templates"
    static let maximumBytes = 65_536

    /// Newest first: a template with the same name (ignoring case) or identifier is replaced, and
    /// only the newest twelve are kept.
    public static func adding(_ template: PlannedJourneyTemplate, to templates: [PlannedJourneyTemplate]) -> [PlannedJourneyTemplate] {
        let others = templates.filter { $0.name.lowercased() != template.name.lowercased() && $0.id != template.id }
        return Array(([template] + others).prefix(maximumTemplates))
    }

    /// Android's document: `{"kind":"binario_planned_journey_templates","schemaVersion":1,"templates":[…]}`.
    public static func encode(_ templates: [PlannedJourneyTemplate]) -> Data {
        let records = templates.prefix(maximumTemplates).map { template -> String in
            var fields: [(String, String)] = [
                ("id", PlannedJourneyJSON.string(template.id)),
                ("name", PlannedJourneyJSON.string(template.name)),
                ("origin", PlannedJourneyJSON.station(template.origin)),
                ("destination", PlannedJourneyJSON.station(template.destination)),
                ("time", PlannedJourneyJSON.string(template.time.hhmm)),
            ]
            if let minutes = template.reminderMinutes {
                fields.append(("reminderMinutes", String(minutes)))
            }
            fields.append(("checklist", PlannedJourneyJSON.array(template.checklist.map(PlannedJourneyJSON.string))))
            return PlannedJourneyJSON.object(fields)
        }
        let document = PlannedJourneyJSON.object([
            ("kind", PlannedJourneyJSON.string(kind)),
            ("schemaVersion", String(PlannedJourneySchema.version)),
            ("templates", PlannedJourneyJSON.array(Array(records))),
        ])
        return Data(document.utf8)
    }

    /// The readable, valid templates of a stored document (the first twelve records, each
    /// identifier once); anything else reads as no template.
    public static func decode(_ data: Data?) -> [PlannedJourneyTemplate] {
        guard let data, !data.isEmpty, data.count <= maximumBytes,
              PlannedJourneyTransfer.nestingDepth(data) <= PlannedJourneySchema.maximumNestingDepth,
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              root["kind"] as? String == kind, root["schemaVersion"] as? Int == PlannedJourneySchema.version,
              let records = root["templates"] as? [Any]
        else { return [] }
        var identifiers = Set<String>()
        return records.prefix(maximumTemplates).compactMap { record -> PlannedJourneyTemplate? in
            guard let value = record as? [String: Any], let template = template(value),
                  identifiers.insert(template.id).inserted
            else { return nil }
            return template
        }
    }

    private static func template(_ value: [String: Any]) -> PlannedJourneyTemplate? {
        guard let id = value["id"] as? String, let name = value["name"] as? String,
              let origin = station(value["origin"]), let destination = station(value["destination"]),
              let time = (value["time"] as? String).flatMap({ PlannedLocalTime(hhmm: $0) }),
              let checklist = value["checklist"] as? [String]
        else { return nil }
        var reminder: Int?
        if let minutes = value["reminderMinutes"] {
            guard let number = minutes as? Int else { return nil }
            reminder = number
        }
        return PlannedJourneyTemplate(
            id: id, name: name, origin: origin, destination: destination, time: time,
            reminderMinutes: reminder, checklist: checklist
        )
    }

    private static func station(_ value: Any?) -> PlannedJourneyStation? {
        guard let fields = value as? [String: Any], let id = fields["canonicalId"] as? String,
              let name = fields["displayName"] as? String
        else { return nil }
        let code = fields["railwayCode"] as? String
        if fields["railwayCode"] != nil, code == nil { return nil }
        return try? PlannedJourneyStation(canonicalId: id, displayName: name, railwayCode: code)
    }
}

/// The templates in the app's `UserDefaults` (Android's document), on this iPhone only.
public final class PlannedJourneyTemplateStore {
    public static let storageKey = "it.binario.ios.agenda-templates.v1"

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Newest first; an unreadable stored value reads as none.
    public func load() -> [PlannedJourneyTemplate] {
        PlannedJourneyTemplates.decode(defaults.data(forKey: Self.storageKey))
    }

    /// The templates after `template` is saved first.
    public func save(_ template: PlannedJourneyTemplate) -> [PlannedJourneyTemplate] {
        let templates = PlannedJourneyTemplates.adding(template, to: load())
        defaults.set(PlannedJourneyTemplates.encode(templates), forKey: Self.storageKey)
        return templates
    }

    /// The templates after the one with `id` is removed; none left removes the stored value.
    public func delete(id: String) -> [PlannedJourneyTemplate] {
        let templates = load().filter { $0.id != id }
        if templates.isEmpty {
            defaults.removeObject(forKey: Self.storageKey)
        } else {
            defaults.set(PlannedJourneyTemplates.encode(templates), forKey: Self.storageKey)
        }
        return templates
    }

    /// Every template goes («Svuota l'Agenda»).
    public func clear() {
        defaults.removeObject(forKey: Self.storageKey)
    }
}

/// The Agenda's preferences on this iPhone, never in its files.
public final class PlannedJourneyAgendaPreferences {
    public static let defaultReminderKey = "it.binario.ios.agenda-default-reminder.v1"

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The lead proposed for a new journey (Android's default lead): nil proposes none, also when
    /// nothing was chosen yet or the stored value is not one of Android's leads.
    public var defaultReminderMinutes: Int? {
        get {
            let minutes = defaults.integer(forKey: Self.defaultReminderKey)
            return PlannedJourneySchema.reminderMinutes.contains(minutes) ? minutes : nil
        }
        set {
            if let newValue, PlannedJourneySchema.reminderMinutes.contains(newValue) {
                defaults.set(newValue, forKey: Self.defaultReminderKey)
            } else {
                defaults.removeObject(forKey: Self.defaultReminderKey)
            }
        }
    }
}
