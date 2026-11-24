import AppIntents
import BinarioKit
import Foundation
import WidgetKit

// The edit sheet of each widget: one `WidgetConfigurationIntent` per widget, with Android's settings in
// the order of its configure screen. Every parameter has a default, as a new Android widget has: an
// added widget works before it is edited (automatic refresh off, as Android's new widgets).

/// «Tratta».
struct RouteWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource { "widgets.route.name" }
    static var description: IntentDescription { IntentDescription("widgets.route.description") }

    @Parameter(title: "widgets.param.origin") var origin: StationEntity?
    @Parameter(title: "widgets.param.destination") var destination: StationEntity?
    @Parameter(title: "widgets.param.title", default: "") var customTitle: String

    @Parameter(title: "widgets.param.refresh", default: .off) var refresh: RefreshModeChoice
    @Parameter(title: "widgets.param.interval", default: .every30) var interval: IntervalChoice
    @Parameter(title: "widgets.param.days", default: .weekdays) var days: DaysChoice
    @Parameter(title: "widgets.param.day.1", default: true) var monday: Bool
    @Parameter(title: "widgets.param.day.2", default: true) var tuesday: Bool
    @Parameter(title: "widgets.param.day.3", default: true) var wednesday: Bool
    @Parameter(title: "widgets.param.day.4", default: true) var thursday: Bool
    @Parameter(title: "widgets.param.day.5", default: true) var friday: Bool
    @Parameter(title: "widgets.param.day.6", default: false) var saturday: Bool
    @Parameter(title: "widgets.param.day.7", default: false) var sunday: Bool
    @Parameter(title: "widgets.param.window1.start", default: .t0700) var firstWindowStart: ClockTimeChoice
    @Parameter(title: "widgets.param.window1.end", default: .t0930) var firstWindowEnd: ClockTimeChoice
    @Parameter(title: "widgets.param.window2", default: false) var secondWindow: Bool
    @Parameter(title: "widgets.param.window2.start", default: .t1700) var secondWindowStart: ClockTimeChoice
    @Parameter(title: "widgets.param.window2.end", default: .t1930) var secondWindowEnd: ClockTimeChoice

    @Parameter(title: "widgets.param.return", default: false) var showReturn: Bool
    @Parameter(title: "widgets.param.return_from", default: .t1400) var returnFrom: ClockTimeChoice
    @Parameter(title: "widgets.param.range", default: .next) var timeRange: TimeRangeChoice

    @Parameter(title: "widgets.param.hide.high_speed", default: false) var hideHighSpeed: Bool
    @Parameter(title: "widgets.param.hide.intercity_night", default: false) var hideIntercity: Bool
    @Parameter(title: "widgets.param.hide.regional_suburban", default: false) var hideRegional: Bool
    @Parameter(title: "widgets.param.hide.italo", default: false) var hideItalo: Bool
    @Parameter(title: "widgets.param.hide.replacement_bus", default: false) var hideBus: Bool
    @Parameter(title: "widgets.param.hide.international", default: false) var hideInternational: Bool

    @Parameter(title: "widgets.param.until_arrival", default: false) var untilArrival: Bool
    @Parameter(title: "widgets.param.platform", default: true) var showPlatform: Bool
    @Parameter(title: "widgets.param.train_name", default: true) var showTrainName: Bool

    init() {}
}

/// «Tabellone stazione».
struct StationWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource { "widgets.station.name" }
    static var description: IntentDescription { IntentDescription("widgets.station.description") }

    @Parameter(title: "widgets.param.station") var station: StationEntity?
    @Parameter(title: "widgets.param.title", default: "") var customTitle: String

    @Parameter(title: "widgets.param.refresh", default: .off) var refresh: RefreshModeChoice
    @Parameter(title: "widgets.param.interval", default: .every30) var interval: IntervalChoice
    @Parameter(title: "widgets.param.days", default: .weekdays) var days: DaysChoice
    @Parameter(title: "widgets.param.day.1", default: true) var monday: Bool
    @Parameter(title: "widgets.param.day.2", default: true) var tuesday: Bool
    @Parameter(title: "widgets.param.day.3", default: true) var wednesday: Bool
    @Parameter(title: "widgets.param.day.4", default: true) var thursday: Bool
    @Parameter(title: "widgets.param.day.5", default: true) var friday: Bool
    @Parameter(title: "widgets.param.day.6", default: false) var saturday: Bool
    @Parameter(title: "widgets.param.day.7", default: false) var sunday: Bool
    @Parameter(title: "widgets.param.window1.start", default: .t0700) var firstWindowStart: ClockTimeChoice
    @Parameter(title: "widgets.param.window1.end", default: .t0930) var firstWindowEnd: ClockTimeChoice
    @Parameter(title: "widgets.param.window2", default: false) var secondWindow: Bool
    @Parameter(title: "widgets.param.window2.start", default: .t1700) var secondWindowStart: ClockTimeChoice
    @Parameter(title: "widgets.param.window2.end", default: .t1930) var secondWindowEnd: ClockTimeChoice

    @Parameter(title: "widgets.param.hide.high_speed", default: false) var hideHighSpeed: Bool
    @Parameter(title: "widgets.param.hide.intercity_night", default: false) var hideIntercity: Bool
    @Parameter(title: "widgets.param.hide.regional_suburban", default: false) var hideRegional: Bool
    @Parameter(title: "widgets.param.hide.italo", default: false) var hideItalo: Bool
    @Parameter(title: "widgets.param.hide.replacement_bus", default: false) var hideBus: Bool
    @Parameter(title: "widgets.param.hide.international", default: false) var hideInternational: Bool

    @Parameter(title: "widgets.param.platform", default: true) var showPlatform: Bool
    @Parameter(title: "widgets.param.train_name", default: true) var showTrainName: Bool
    @Parameter(title: "widgets.param.nearest", default: false) var showNearest: Bool

    init() {}
}

/// «I miei treni».
struct TrainsWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource { "widgets.trains.name" }
    static var description: IntentDescription { IntentDescription("widgets.trains.description") }

    @Parameter(title: "widgets.param.title", default: "") var customTitle: String
    @Parameter(title: "widgets.param.info", default: .nextStation) var info: TrainsInfoChoice
    @Parameter(title: "widgets.param.platform", default: true) var showPlatform: Bool
    @Parameter(title: "widgets.param.hide_not_running", default: false) var hideNotRunning: Bool
    @Parameter(title: "widgets.param.sort", default: .departure) var sort: TrainsSortChoice

    @Parameter(title: "widgets.param.refresh", default: .off) var refresh: RefreshModeChoice
    @Parameter(title: "widgets.param.interval", default: .every30) var interval: IntervalChoice
    @Parameter(title: "widgets.param.days", default: .weekdays) var days: DaysChoice
    @Parameter(title: "widgets.param.day.1", default: true) var monday: Bool
    @Parameter(title: "widgets.param.day.2", default: true) var tuesday: Bool
    @Parameter(title: "widgets.param.day.3", default: true) var wednesday: Bool
    @Parameter(title: "widgets.param.day.4", default: true) var thursday: Bool
    @Parameter(title: "widgets.param.day.5", default: true) var friday: Bool
    @Parameter(title: "widgets.param.day.6", default: false) var saturday: Bool
    @Parameter(title: "widgets.param.day.7", default: false) var sunday: Bool
    @Parameter(title: "widgets.param.window1.start", default: .t0700) var firstWindowStart: ClockTimeChoice
    @Parameter(title: "widgets.param.window1.end", default: .t0930) var firstWindowEnd: ClockTimeChoice
    @Parameter(title: "widgets.param.window2", default: false) var secondWindow: Bool
    @Parameter(title: "widgets.param.window2.start", default: .t1700) var secondWindowStart: ClockTimeChoice
    @Parameter(title: "widgets.param.window2.end", default: .t1930) var secondWindowEnd: ClockTimeChoice

    init() {}
}

// MARK: - Buttons (interactive widgets, iOS 17)

/// The direction button of «Tratta»: the other direction, until the next automatic change when
/// «Mostra il ritorno dalle» is on (Android's `WidgetStateStore.switchDirection`).
struct SwitchRouteDirectionIntent: AppIntent {
    static var title: LocalizedStringResource { "widgets.action.direction" }
    static var isDiscoverable: Bool { false }

    @Parameter(title: "widgets.param.origin") var routeKey: String
    /// Minutes from midnight of «ritorno dalle», or -1 without it.
    @Parameter(title: "widgets.param.return_from") var returnFromMinute: Int

    init() {}

    init(routeKey: String, returnFrom: PlannedLocalTime?) {
        self.routeKey = routeKey
        self.returnFromMinute = returnFrom?.minutesOfDay ?? -1
    }

    func perform() async throws -> some IntentResult {
        WidgetActions.switchDirection(routeKey: routeKey, returnFromMinute: returnFromMinute, now: Date())
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetKindName.route.rawValue)
        return .result()
    }
}

/// «↻»: refreshes every widget of its kind, as Android's button does.
struct RefreshWidgetIntent: AppIntent {
    static var title: LocalizedStringResource { "widgets.action.route_refresh" }
    static var isDiscoverable: Bool { false }

    @Parameter(title: "widgets.param.title") var kind: String

    init() {}

    init(kind: WidgetKindName) {
        self.kind = kind.rawValue
    }

    func perform() async throws -> some IntentResult {
        if let kind = WidgetKindName(rawValue: kind) {
            WidgetSharedStore.shared()?.requestRefresh(kind, at: Date())
            WidgetCenter.shared.reloadTimelines(ofKind: kind.rawValue)
        }
        return .result()
    }
}

/// «‹» and «›»: the page of a widget's rows (Android keeps a row offset per widget).
struct PageWidgetIntent: AppIntent {
    static var title: LocalizedStringResource { "widgets.action.next" }
    static var isDiscoverable: Bool { false }

    @Parameter(title: "widgets.param.title") var kind: String
    @Parameter(title: "widgets.param.title") var key: String
    @Parameter(title: "widgets.param.title") var offset: Int

    init() {}

    init(kind: WidgetKindName, key: String, offset: Int) {
        self.kind = kind.rawValue
        self.key = key
        self.offset = offset
    }

    func perform() async throws -> some IntentResult {
        WidgetSharedStore.shared()?.setRowOffset(offset, key: key)
        WidgetCenter.shared.reloadTimelines(ofKind: kind)
        return .result()
    }
}
