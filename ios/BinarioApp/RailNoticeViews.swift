import SwiftUI
import BinarioKit

/// The words of the notice sections, Android's strings (NoticeComponents, RailNoticesArea,
/// RouteWorksSection). Brands are proper names, never translated.
enum NoticeTexts {
    private static let rome = TimeZone(identifier: "Europe/Rome") ?? .current

    /// «Pubblicato da RFI, 23/11/26, 10:00», in the Italian railway time.
    static func published(_ notice: RailNotice) -> String {
        guard let date = notice.publishedAt else {
            return String.localizedStringWithFormat(NSLocalizedString("notices.published_by", comment: ""), notice.publisher.brand)
        }
        let time = date.formatted(Date.FormatStyle(date: .numeric, time: .shortened, timeZone: rome))
        return String.localizedStringWithFormat(NSLocalizedString("notices.published_by_at", comment: ""), notice.publisher.brand, time)
    }

    /// «Non raggiungibile ora: Trenord».
    static func unavailable(_ publisher: NoticePublisher) -> String {
        String.localizedStringWithFormat(NSLocalizedString("notices.unavailable_publisher", comment: ""), publisher.brand)
    }

    /// «Treni garantiti in caso di sciopero (Trenitalia)».
    static func guaranteedTrains(_ publisher: NoticePublisher) -> String {
        String.localizedStringWithFormat(NSLocalizedString("notices.guaranteed_trains", comment: ""), publisher.brand)
    }

    /// «Direttrice D034 · LAVENO-VARESE-SARONNO-MILANO» for one line, «Direttrici D034, D010» for several.
    static func lines(_ lines: [NoticeLine], allLines: Bool, rules: NoticeRules) -> String? {
        if allLines { return NSLocalizedString("notices.trenord_all_lines", comment: "") }
        guard let first = lines.first else { return nil }
        if lines.count == 1 {
            if first.name.trimmingCharacters(in: .whitespaces).isEmpty {
                return String.localizedStringWithFormat(NSLocalizedString("notices.trenord_line_code", comment: ""), first.code)
            }
            return String.localizedStringWithFormat(NSLocalizedString("notices.trenord_line", comment: ""), first.code, first.name)
        }
        let limit = rules.display.maximumLineCodes
        let codes = lines.prefix(limit).map(\.code).joined(separator: ", ") + (lines.count > limit ? ", …" : "")
        return String.localizedStringWithFormat(NSLocalizedString("notices.trenord_lines", comment: ""), codes)
    }

    /// A day of the Italian calendar, as `style` writes it in the user's language.
    static func day(_ date: PlannedLocalDate, _ style: Date.FormatStyle) -> String {
        guard let noon = PlannedLocalTime(hour: 12, minute: 0),
              let instant = ItalianWallClock.instant(date: date, time: noon) else { return date.iso }
        return instant.formatted(style)
    }

    /// «3 dic 2026», a strike's day.
    static func strikeDay(_ date: PlannedLocalDate) -> String {
        day(date, Date.FormatStyle(date: .abbreviated, time: .omitted, timeZone: rome))
    }

    /// «14 nov», a day a works notice names.
    static func worksDay(_ date: PlannedLocalDate) -> String {
        day(date, Date.FormatStyle(timeZone: rome).day().month(.abbreviated))
    }

    /// «Date citate: dal 14 nov al 16 nov», or the one day the notice names.
    static func worksDates(_ dates: NoticeDates) -> String {
        if dates.first == dates.last {
            return String.localizedStringWithFormat(NSLocalizedString("notices.works.date", comment: ""), worksDay(dates.first))
        }
        return String.localizedStringWithFormat(
            NSLocalizedString("notices.works.dates", comment: ""), worksDay(dates.first), worksDay(dates.last)
        )
    }

    /// «L’avviso cita: la linea Milano – Venezia, Brescia», with the route's name when the section
    /// shows several routes.
    static func reason(_ reason: RouteWorksReason, showsRoute: Bool) -> String {
        let line = reason.line.map { String.localizedStringWithFormat(NSLocalizedString("notices.works.line", comment: ""), $0) }
        let named = ((line.map { [$0] } ?? []) + reason.stations).joined(separator: ", ")
        if showsRoute {
            return String.localizedStringWithFormat(NSLocalizedString("notices.works.route_names", comment: ""), reason.routeName, named)
        }
        return String.localizedStringWithFormat(NSLocalizedString("notices.works.names", comment: ""), named)
    }

    /// «Mostra altri 2 avvisi».
    static func showMore(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("notices.works.show_more", comment: ""), count)
    }

    /// A page the browser opens on a tap: http(s) only.
    static func webPage(_ value: String?) -> URL? {
        guard let value, let url = URL(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http" else { return nil }
        return url
    }
}

/// «Sciopero»: on the notices whose text mentions a strike, and on the MIT calendar's strikes.
struct StrikeBadge: View {
    var body: some View {
        Text("notices.strike_badge")
            .font(.caption.weight(.bold))
            .foregroundStyle(BinarioPalette.statusError)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(BinarioPalette.statusError.opacity(0.12), in: RoundedRectangle(cornerRadius: 6))
    }
}

/// Notice text whose URLs open in the browser on a tap, everything else plain. A notice that came
/// over the cleartext ViaggiaTreno hop makes only HTTPS links to the publisher's own domains tappable,
/// whatever the text went through before.
struct NoticeLinkedText: View {
    let text: String
    let notice: RailNotice
    let rules: NoticeRules

    var body: some View {
        Text(attributed)
    }

    private var attributed: AttributedString {
        var result = AttributedString()
        for segment in NoticeLinks.segments(text, rules: rules, allow: { notice.allowsLink($0, rules: rules) }) {
            var part = AttributedString(segment.text)
            if case let .link(address) = segment, let url = NoticeTexts.webPage(address) {
                part.link = url
            }
            result += part
        }
        return result
    }
}

/// One network notice: the strike badge, title, the body collapsed to a few lines, publisher and
/// time, the publisher's page and, for a strike of Trenitalia or Trenord, its guaranteed trains.
struct RailNoticeRow: View {
    let notice: RailNotice
    let rules: NoticeRules
    var showsLines = true
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if notice.mentionsStrike {
                StrikeBadge()
            }
            if !notice.titleInBody {
                NoticeLinkedText(text: notice.title, notice: notice, rules: rules)
                    .font(.subheadline.weight(.semibold))
            }
            if showsLines, let lines = NoticeTexts.lines(notice.lines, allLines: false, rules: rules) {
                Text(verbatim: lines)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let text = notice.body {
                NoticeLinkedText(text: text, notice: notice, rules: rules)
                    .font(notice.titleInBody ? .subheadline : .footnote)
                    .lineLimit(expanded ? nil : rules.display.bodyCollapsedLines)
                if text.count > rules.display.bodyCollapsedCharacters
                    || text.filter({ $0 == "\n" }).count >= rules.display.bodyCollapsedLines {
                    Button(LocalizedStringKey(expanded ? "notices.collapse" : "notices.expand")) { expanded.toggle() }
                        .buttonStyle(.borderless)
                        .font(.footnote)
                }
            }
            Text(verbatim: NoticeTexts.published(notice))
                .font(.caption)
                .foregroundStyle(.secondary)
            if let link = notice.link, notice.allowsLink(link, rules: rules), let url = NoticeTexts.webPage(link) {
                Link("notices.open_source", destination: url)
                    .buttonStyle(.borderless)
                    .font(.footnote)
            }
            if notice.mentionsStrike, let page = rules.guaranteedTrains(of: notice.publisher) {
                Link(NoticeTexts.guaranteedTrains(notice.publisher), destination: page)
                    .buttonStyle(.borderless)
                    .font(.footnote)
            }
        }
        .padding(.vertical, 2)
    }
}

/// «Scioperi in programma»: the day (or the days), title and announcement page of each rail strike
/// of the MIT calendar.
private struct UpcomingStrikeRow: View {
    let strike: StrikeNotice

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            StrikeBadge()
            if let start = strike.startDate {
                if let end = strike.endDate, end != start {
                    Text(verbatim: String.localizedStringWithFormat(
                        NSLocalizedString("notices.strikes.date_range", comment: ""), NoticeTexts.strikeDay(start), NoticeTexts.strikeDay(end)
                    ))
                    .font(.footnote.weight(.bold))
                } else {
                    Text(verbatim: NoticeTexts.strikeDay(start))
                        .font(.footnote.weight(.bold))
                }
            } else if let published = strike.publishedAt, let day = ItalianWallClock.date(of: published) {
                Text(verbatim: String.localizedStringWithFormat(
                    NSLocalizedString("notices.strikes.published_on", comment: ""), NoticeTexts.strikeDay(day)
                ))
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Text(verbatim: strike.title)
                .font(.subheadline.weight(.semibold))
            if let url = NoticeTexts.webPage(strike.link) {
                Link("notices.open_source", destination: url)
                    .buttonStyle(.borderless)
                    .font(.footnote)
            }
        }
        .padding(.vertical, 2)
    }
}

/// The bottom of «Partenze», as the bottom of Android's departures board: the Trenord notices of
/// the board's station (only in the Trenord area, with «Dati in tempo reale» on) and «Avvisi e
/// infomobilità» (RFI and the MIT calendar always; Trenitalia and Trenord with the switch on).
/// Nothing is downloaded until the user opens the area; the station's notices when the area's rows
/// appear.
struct BoardNoticesSections: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var notices: RailNoticesModel
    let station: Station?
    @State private var open = false
    @State private var showsAll = false

    private struct LoadKey: Equatable {
        let open: Bool
        let personal: Bool
        let station: String?
    }

    var body: some View {
        if let rules = notices.rules {
            if let station, notices.showsTrenordNotices(at: station, personal: model.liveDataEnabled),
               let board = notices.stations[RailNoticesModel.stationKey(station, personal: model.liveDataEnabled)],
               !board.notices.isEmpty || board.unavailable {
                Section("notices.station_trenord_title") {
                    ForEach(board.notices.indices, id: \.self) { index in
                        RailNoticeRow(notice: board.notices[index], rules: rules)
                    }
                    if board.stale { NoticeNote(key: "notices.stale") }
                    if board.unavailable { NoticeNote(key: "notices.unavailable") }
                }
            }
            Section {
                Text("notices.area.description")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .task(id: LoadKey(open: open, personal: model.liveDataEnabled, station: station?.id)) { load() }
                if open, !notices.upcomingStrikes.isEmpty {
                    Text("notices.strikes.title")
                        .font(.subheadline.weight(.bold))
                    ForEach(notices.upcomingStrikes) { strike in
                        UpcomingStrikeRow(strike: strike)
                    }
                    NoticeNote(key: "notices.strikes.source")
                }
                if let calendar = rules.mitStrikeCalendar {
                    Link("notices.strike_calendar", destination: calendar)
                }
                // The FS Italiane news site with its radio bulletins: a link, no audio is played in the app.
                if let news = rules.fsNews {
                    Link("notices.fs_news_bulletin", destination: news)
                }
                if open {
                    areaContent(rules)
                } else {
                    Button("notices.area.show") { open = true }
                }
            } header: {
                Text("notices.area.title")
            }
        }
    }

    @ViewBuilder
    private func areaContent(_ rules: NoticeRules) -> some View {
        if let value = notices.general?.value {
            Text("notices.network_heading")
                .font(.subheadline.weight(.bold))
            if value.network.isEmpty { NoticeNote(key: "notices.area.empty") }
            let shown = showsAll ? value.network : Array(value.network.prefix(rules.display.networkCollapsed))
            ForEach(shown.indices, id: \.self) { index in
                RailNoticeRow(notice: shown[index], rules: rules)
            }
            if !showsAll, value.network.count > shown.count {
                Button("notices.area.show_all") { showsAll = true }
            }
            if !value.trenordGroups.isEmpty {
                Text("notices.trenord_heading")
                    .font(.subheadline.weight(.bold))
                ForEach(value.trenordGroups.indices, id: \.self) { index in
                    let group = value.trenordGroups[index]
                    if let label = NoticeTexts.lines(group.lines, allLines: group.allLines, rules: rules) {
                        Text(verbatim: label)
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.tint)
                    }
                    ForEach(group.notices.indices, id: \.self) { position in
                        RailNoticeRow(notice: group.notices[position], rules: rules, showsLines: false)
                    }
                }
            }
            if value.stale { NoticeNote(key: "notices.stale") }
            ForEach(value.unavailable.sorted(), id: \.self) { publisher in
                Text(verbatim: NoticeTexts.unavailable(publisher))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Button("notices.area.refresh") { notices.loadGeneral(personal: model.liveDataEnabled, force: true) }
        } else {
            HStack {
                ProgressView()
                Text("notices.loading")
            }
        }
    }

    private func load() {
        if open { notices.loadGeneral(personal: model.liveDataEnabled) }
        if let station { notices.loadStation(station, personal: model.liveDataEnabled) }
    }
}

/// A secondary note of the notice sections.
private struct NoticeNote: View {
    let key: LocalizedStringKey

    var body: some View {
        Text(key)
            .font(.footnote)
            .foregroundStyle(.secondary)
    }
}

/// Which works section: the saved routes of «Tratte», or the saved route the planner shows.
enum RouteWorksScope {
    case savedRoutes
    case thisRoute
}

/// «Lavori e interruzioni sulle tue tratte» («… su questa tratta»): the network notices about works
/// or interruptions whose text names an end of the routes or a line between them, with the route,
/// what the text names and the dates it gives. Read when the screen shows it
/// (`View.loadsRouteWorks`); hidden while it is read, and, in the planner, when no notice names the
/// route.
struct RouteWorksSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var notices: RailNoticesModel
    let routes: [SavedRoute]
    let scope: RouteWorksScope
    @State private var expanded = false

    var body: some View {
        let shown = notices.worksRoutes(routes)
        if let rules = notices.rules, !shown.isEmpty,
           let works = notices.works[RailNoticesModel.worksKey(shown, personal: model.liveDataEnabled)],
           !works.notices.isEmpty || scope == .savedRoutes || works.unavailable {
            Section {
                if works.notices.isEmpty, !works.unavailable { NoticeNote(key: "notices.works.none") }
                let visible = expanded ? works.notices : Array(works.notices.prefix(rules.display.worksCollapsed))
                ForEach(visible.indices, id: \.self) { position in
                    let item = visible[position]
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(item.reasons.indices, id: \.self) { index in
                            Text(verbatim: NoticeTexts.reason(item.reasons[index], showsRoute: shown.count > 1))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.tint)
                        }
                        if let dates = item.dates {
                            Text(verbatim: NoticeTexts.worksDates(dates))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        RailNoticeRow(notice: item.notice, rules: rules, showsLines: false)
                    }
                }
                if works.notices.count > visible.count {
                    Button(NoticeTexts.showMore(works.notices.count - visible.count)) { expanded = true }
                }
                if !works.notices.isEmpty { NoticeNote(key: "notices.works.note") }
                if works.stale { NoticeNote(key: "notices.stale") }
                if works.unavailable { NoticeNote(key: "notices.unavailable") }
            } header: {
                switch scope {
                case .savedRoutes: Text("notices.works.title")
                case .thisRoute: Text("notices.works.title_route")
                }
            }
        }
    }
}

extension View {
    /// Reads the works of `routes` while this screen is shown (again after the reload interval).
    @MainActor
    func loadsRouteWorks(_ routes: [SavedRoute], model: AppModel, notices: RailNoticesModel) -> some View {
        let shown = notices.worksRoutes(routes)
        // The catalogue's names are needed: a catalogue loaded later reads the works then.
        let catalogue: [Station]
        if case let .available(catalog) = model.catalogState { catalogue = catalog.stations } else { catalogue = [] }
        let key = RailNoticesModel.worksKey(shown, personal: model.liveDataEnabled) + (catalogue.isEmpty ? "" : "|catalogue")
        return task(id: key) {
            guard !shown.isEmpty, !catalogue.isEmpty else { return }
            let stations = shown.map { route in
                WorksRoute(id: route.id, name: route.name, stations: [model.station(for: route.origin), model.station(for: route.destination)])
            }
            notices.loadWorks(shown, stations: stations, catalogue: catalogue, personal: model.liveDataEnabled)
        }
    }
}
