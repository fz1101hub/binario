import AppIntents
import BinarioKit
import Foundation
import WidgetKit

// The settings of each widget instance, as the parameters of its edit sheet (iOS 17
// `AppIntentConfiguration`, one intent per widget): Android's configure screens, setting for setting
// (WidgetConfigureActivity, StationWidgetConfigureActivity, TrainsWidgetConfigureActivity). The texts
// are Android's (tools/ios_widget_strings.py); the defaults are a new Android widget's.

// MARK: - Choices

/// «Aggiornamento automatico».
enum RefreshModeChoice: String, AppEnum {
    case off, windows, always

    static var typeDisplayRepresentation: TypeDisplayRepresentation { "widgets.param.refresh" }

    static var caseDisplayRepresentations: [RefreshModeChoice: DisplayRepresentation] {
        [.off: "widgets.refresh.off", .windows: "widgets.refresh.windows", .always: "widgets.refresh.always"]
    }

    var mode: WidgetAutoRefreshMode {
        switch self {
        case .off: return .off
        case .windows: return .windows
        case .always: return .always
        }
    }
}

/// «Frequenza» of the windows.
enum IntervalChoice: String, AppEnum {
    case every15, every30, every60

    static var typeDisplayRepresentation: TypeDisplayRepresentation { "widgets.param.interval" }

    static var caseDisplayRepresentations: [IntervalChoice: DisplayRepresentation] {
        [.every15: "widgets.interval.15", .every30: "widgets.interval.30", .every60: "widgets.interval.60"]
    }

    var minutes: Int {
        switch self {
        case .every15: return 15
        case .every30: return 30
        case .every60: return 60
        }
    }
}

/// «Giorni»: Monday to Friday, every day, or the days chosen below.
enum DaysChoice: String, AppEnum {
    case weekdays, everyDay, custom

    static var typeDisplayRepresentation: TypeDisplayRepresentation { "widgets.param.days" }

    static var caseDisplayRepresentations: [DaysChoice: DisplayRepresentation] {
        [.weekdays: "widgets.days.weekdays", .everyDay: "widgets.days.every_day", .custom: "widgets.days.custom"]
    }
}

/// «Corse mostrate».
enum TimeRangeChoice: String, AppEnum {
    case next, next3Hours, next6Hours, wholeDay

    static var typeDisplayRepresentation: TypeDisplayRepresentation { "widgets.param.range" }

    static var caseDisplayRepresentations: [TimeRangeChoice: DisplayRepresentation] {
        [
            .next: "widgets.range.next", .next3Hours: "widgets.range.next_3h",
            .next6Hours: "widgets.range.next_6h", .wholeDay: "widgets.range.whole_day",
        ]
    }

    var range: WidgetTimeRange {
        switch self {
        case .next: return .next
        case .next3Hours: return .next3Hours
        case .next6Hours: return .next6Hours
        case .wholeDay: return .wholeDay
        }
    }
}

/// «Per ogni treno mostra».
enum TrainsInfoChoice: String, AppEnum {
    case nextStation, lastDetection

    static var typeDisplayRepresentation: TypeDisplayRepresentation { "widgets.param.info" }

    static var caseDisplayRepresentations: [TrainsInfoChoice: DisplayRepresentation] {
        [.nextStation: "widgets.info.next_station", .lastDetection: "widgets.info.last_detection"]
    }
}

/// «Ordine».
enum TrainsSortChoice: String, AppEnum {
    case departure, name

    static var typeDisplayRepresentation: TypeDisplayRepresentation { "widgets.param.sort" }

    static var caseDisplayRepresentations: [TrainsSortChoice: DisplayRepresentation] {
        [.departure: "widgets.sort.departure", .name: "widgets.sort.name"]
    }
}

/// A time of day of a refresh window or of «Mostra il ritorno dalle», in quarter hours: the edit sheet of a widget
/// has no time picker, so the times are a list (Android's time picker takes any minute).
enum ClockTimeChoice: String, AppEnum {
    case t0000, t0015, t0030, t0045, t0100, t0115, t0130, t0145
    case t0200, t0215, t0230, t0245, t0300, t0315, t0330, t0345
    case t0400, t0415, t0430, t0445, t0500, t0515, t0530, t0545
    case t0600, t0615, t0630, t0645, t0700, t0715, t0730, t0745
    case t0800, t0815, t0830, t0845, t0900, t0915, t0930, t0945
    case t1000, t1015, t1030, t1045, t1100, t1115, t1130, t1145
    case t1200, t1215, t1230, t1245, t1300, t1315, t1330, t1345
    case t1400, t1415, t1430, t1445, t1500, t1515, t1530, t1545
    case t1600, t1615, t1630, t1645, t1700, t1715, t1730, t1745
    case t1800, t1815, t1830, t1845, t1900, t1915, t1930, t1945
    case t2000, t2015, t2030, t2045, t2100, t2115, t2130, t2145
    case t2200, t2215, t2230, t2245, t2300, t2315, t2330, t2345

    static var typeDisplayRepresentation: TypeDisplayRepresentation { "widgets.param.time" }

    static var caseDisplayRepresentations: [ClockTimeChoice: DisplayRepresentation] {
        [
            .t0000: "00:00",
            .t0015: "00:15",
            .t0030: "00:30",
            .t0045: "00:45",
            .t0100: "01:00",
            .t0115: "01:15",
            .t0130: "01:30",
            .t0145: "01:45",
            .t0200: "02:00",
            .t0215: "02:15",
            .t0230: "02:30",
            .t0245: "02:45",
            .t0300: "03:00",
            .t0315: "03:15",
            .t0330: "03:30",
            .t0345: "03:45",
            .t0400: "04:00",
            .t0415: "04:15",
            .t0430: "04:30",
            .t0445: "04:45",
            .t0500: "05:00",
            .t0515: "05:15",
            .t0530: "05:30",
            .t0545: "05:45",
            .t0600: "06:00",
            .t0615: "06:15",
            .t0630: "06:30",
            .t0645: "06:45",
            .t0700: "07:00",
            .t0715: "07:15",
            .t0730: "07:30",
            .t0745: "07:45",
            .t0800: "08:00",
            .t0815: "08:15",
            .t0830: "08:30",
            .t0845: "08:45",
            .t0900: "09:00",
            .t0915: "09:15",
            .t0930: "09:30",
            .t0945: "09:45",
            .t1000: "10:00",
            .t1015: "10:15",
            .t1030: "10:30",
            .t1045: "10:45",
            .t1100: "11:00",
            .t1115: "11:15",
            .t1130: "11:30",
            .t1145: "11:45",
            .t1200: "12:00",
            .t1215: "12:15",
            .t1230: "12:30",
            .t1245: "12:45",
            .t1300: "13:00",
            .t1315: "13:15",
            .t1330: "13:30",
            .t1345: "13:45",
            .t1400: "14:00",
            .t1415: "14:15",
            .t1430: "14:30",
            .t1445: "14:45",
            .t1500: "15:00",
            .t1515: "15:15",
            .t1530: "15:30",
            .t1545: "15:45",
            .t1600: "16:00",
            .t1615: "16:15",
            .t1630: "16:30",
            .t1645: "16:45",
            .t1700: "17:00",
            .t1715: "17:15",
            .t1730: "17:30",
            .t1745: "17:45",
            .t1800: "18:00",
            .t1815: "18:15",
            .t1830: "18:30",
            .t1845: "18:45",
            .t1900: "19:00",
            .t1915: "19:15",
            .t1930: "19:30",
            .t1945: "19:45",
            .t2000: "20:00",
            .t2015: "20:15",
            .t2030: "20:30",
            .t2045: "20:45",
            .t2100: "21:00",
            .t2115: "21:15",
            .t2130: "21:30",
            .t2145: "21:45",
            .t2200: "22:00",
            .t2215: "22:15",
            .t2230: "22:30",
            .t2245: "22:45",
            .t2300: "23:00",
            .t2315: "23:15",
            .t2330: "23:30",
            .t2345: "23:45",
        ]
    }

    /// Minutes from midnight.
    var minuteOfDay: Int {
        let digits = rawValue.dropFirst()
        let hours = Int(digits.prefix(2)) ?? 0
        let minutes = Int(digits.suffix(2)) ?? 0
        return hours * 60 + minutes
    }
}

// MARK: - Stations

/// A station of the offline catalogue the app ships (`curated_it_rail_stations.json`).
struct StationEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation { "widgets.param.station" }
    static var defaultQuery: StationEntityQuery { StationEntityQuery() }

    let id: String
    let name: String

    init(id: String, name: String) {
        self.id = id
        self.name = name
    }

    init(_ station: Station) {
        self.init(id: station.id, name: station.name)
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)")
    }
}

/// The catalogue's search, as the app's station fields search it.
struct StationEntityQuery: EntityStringQuery {
    func entities(for identifiers: [String]) async throws -> [StationEntity] {
        guard let catalog = await WidgetCatalog.shared.catalog() else { return [] }
        return identifiers.compactMap { identifier in catalog.station(id: identifier).map { StationEntity($0) } }
    }

    func entities(matching string: String) async throws -> [StationEntity] {
        guard let catalog = await WidgetCatalog.shared.catalog() else { return [] }
        return catalog.search(string, limit: 20).map { StationEntity($0) }
    }

    /// The stations of «I miei treni», the first choices offered before typing.
    func suggestedEntities() async throws -> [StationEntity] {
        guard let store = WidgetSharedStore.shared() else { return [] }
        var seen = Set<String>()
        var stations: [StationEntity] = []
        for train in store.favouriteTrains.load().trains {
            for identity in [train.origin, train.destination] where seen.insert(identity.id).inserted {
                stations.append(StationEntity(id: identity.id, name: identity.name))
            }
        }
        return stations
    }
}

// MARK: - Automatic refresh

/// The automatic refresh of the parameters every widget has.
enum WidgetRefreshSettings {
    static func make(
        mode: RefreshModeChoice, interval: IntervalChoice, days: DaysChoice, chosenDays: [Bool],
        firstWindow: (ClockTimeChoice, ClockTimeChoice), secondWindow: (ClockTimeChoice, ClockTimeChoice)?
    ) -> WidgetAutoRefresh {
        let selected: Set<Int>
        switch days {
        case .weekdays: selected = SharedData.Widgets.weekdays
        case .everyDay: selected = Set(1...7)
        case .custom:
            var custom = Set<Int>()
            for (index, chosen) in chosenDays.enumerated() where chosen { custom.insert(index + 1) }
            selected = custom
        }
        var windows = [WidgetRefreshWindow(startMinute: firstWindow.0.minuteOfDay, endMinute: firstWindow.1.minuteOfDay)]
        if let secondWindow {
            windows.append(WidgetRefreshWindow(startMinute: secondWindow.0.minuteOfDay, endMinute: secondWindow.1.minuteOfDay))
        }
        switch mode {
        case .off: return WidgetAutoRefresh(mode: .off, intervalMinutes: interval.minutes, days: selected, windows: windows)
        case .always: return WidgetAutoRefresh.always
        case .windows: return WidgetAutoRefresh(mode: .windows, intervalMinutes: interval.minutes, days: selected, windows: windows)
        }
    }

    static func excluded(
        highSpeed: Bool, intercityNight: Bool, regionalSuburban: Bool, italo: Bool, replacementBus: Bool, international: Bool
    ) -> Set<WidgetTrainTypeGroup> {
        var groups = Set<WidgetTrainTypeGroup>()
        if highSpeed { groups.insert(.highSpeed) }
        if intercityNight { groups.insert(.intercityNight) }
        if regionalSuburban { groups.insert(.regionalSuburban) }
        if italo { groups.insert(.italo) }
        if replacementBus { groups.insert(.replacementBus) }
        if international { groups.insert(.international) }
        return groups
    }

    /// A custom title, trimmed to Android's 42 characters.
    static func title(_ value: String) -> String {
        String(value.trimmingCharacters(in: .whitespacesAndNewlines).prefix(SharedData.Widgets.maximumTitleLength))
    }
}
