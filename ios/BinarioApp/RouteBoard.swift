import SwiftUI
import BinarioKit
#if canImport(UIKit)
import UIKit
#endif
import EventKit
import EventKitUI

// Android's active-route board, links and quick actions, and «Aggiungi al calendario».

// MARK: «Le tue partenze»: the board of the active route

/// The next trains of the active route (Android's «Treni» section of Partenze): the next one
/// first, then the others, «Direzione» to turn the route round and «Cambia tratta».
struct ActiveRouteBoardSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var personal: PersonalModel
    @State private var result: JourneyResult?
    /// «Ultimo di oggi» and «primo domani alle …» of the route, from the static timetable.
    @State private var staticDay: StaticDayDepartures?
    @State private var reversed = false
    @State private var choosing = false

    var body: some View {
        if let route = personal.activeRoute(in: model.savedRoutes) {
            let origin = reversed ? route.destination : route.origin
            let destination = reversed ? route.origin : route.destination
            Section {
                Text(verbatim: "\(origin.name) → \(destination.name)")
                    .font(.headline)
                HStack {
                    Button {
                        reversed.toggle()
                    } label: {
                        Label("departures.route.direction", systemImage: "arrow.left.arrow.right")
                    }
                    .buttonStyle(.borderless)
                    Spacer()
                    Button {
                        choosing = true
                    } label: {
                        Label("departures.route.change", systemImage: "arrow.triangle.swap")
                    }
                    .buttonStyle(.borderless)
                }
                switch result {
                case nil:
                    ProgressView()
                case let .available(snapshot)?:
                    let upcoming = Array(snapshot.services.prefix(6))
                    if let first = LastOfDayMarks.firstNextDay(staticDay, now: Date()) {
                        Label(JourneyTexts.noMoreToday(first.departure), systemImage: "moon.zzz")
                            .font(.footnote)
                    }
                    ForEach(upcoming.indices, id: \.self) { index in
                        NavigationLink {
                            TrainDetailView(service: upcoming[index], query: snapshot.query)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                if index == 0 {
                                    Text("departures.route.next").font(.caption.weight(.semibold))
                                } else if index == 1 {
                                    Text("departures.route.others").font(.caption.weight(.semibold))
                                }
                                HStack(alignment: .firstTextBaseline) {
                                    Text(upcoming[index].scheduledDeparture)
                                        .font(index == 0 ? .title3.monospacedDigit().weight(.bold) : .headline.monospacedDigit())
                                    Text(verbatim: "\(upcoming[index].category) \(upcoming[index].number)")
                                    Spacer()
                                    if let platform = upcoming[index].platform {
                                        Text(DeparturesTexts.platform(platform))
                                    }
                                }
                                Text(upcoming[index].operatorName)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                if LastOfDayMarks.isLast(staticDay, publicNumber: upcoming[index].number, scheduledDeparture: upcoming[index].departureDate) {
                                    LastOfDayMark()
                                }
                            }
                            .accessibilityElement(children: .combine)
                        }
                    }
                    Label("result.programmed.disclosure", systemImage: "clock")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                case .unavailable?:
                    Text("departures.none.title")
                        .foregroundStyle(.secondary)
                    if let first = LastOfDayMarks.firstNextDay(staticDay, now: Date()) {
                        Text(JourneyTexts.noMoreToday(first.departure))
                            .font(.footnote)
                    }
                }
            } header: {
                Text("departures.route.title")
            }
            .task(id: "\(route.id)|\(reversed)|\(AppModel.boardDay(Date()))") { await load(origin: origin, destination: destination) }
            .sheet(isPresented: $choosing) {
                RoutePickerSheet()
            }
        }
    }

    private func load(origin: StationIdentity, destination: StationIdentity) async {
        result = nil
        staticDay = nil
        guard model.scheduleState == .available,
              let query = try? JourneyQuery(origin: origin, destination: destination, departure: Date()) else {
            result = .unavailable(.noJourney)
            return
        }
        result = await model.repository.journeys(for: query)
        staticDay = await model.staticDayDepartures(origin: origin, destination: destination)
    }
}

/// «Scegli la tratta»: the saved routes, pinned first; the one chosen becomes the active route.
private struct RoutePickerSheet: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var personal: PersonalModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if model.savedRoutes.isEmpty {
                    Text("departures.route.empty")
                        .foregroundStyle(.secondary)
                }
                ForEach(personal.ordered(model.savedRoutes)) { route in
                    Button {
                        personal.setActiveRoute(route.id)
                        dismiss()
                    } label: {
                        Label(route.name, systemImage: personal.activeRoute(in: model.savedRoutes)?.id == route.id ? "checkmark" : "point.topleft.down.to.point.bottomright.curvepath")
                    }
                }
            }
            .navigationTitle("departures.route.pick")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action.cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: Links and quick actions

/// Where a `binario://` link or a Home-screen quick action leads, as Android's `BinarioDeepLink`.
enum AppLink: Equatable {
    case agenda
    case activeRoute
    case nextDepartures
    case trainSearch(String?)
}

@MainActor
enum AppLinks {
    static let notification = Notification.Name("it.binario.ios.app-link")
    /// Asked before the screen could follow (a launch by a quick action).
    private(set) static var pending: AppLink?

    /// Android's allowlist (`BinarioDeepLinkParser`): `binario://agenda`, `binario://route/active`,
    /// `binario://departures/next`, `binario://train/search[?number=2153]`; anything else is ignored.
    static func link(_ url: URL) -> AppLink? {
        guard url.scheme == "binario", let host = url.host(), url.user == nil, url.port == nil, url.fragment == nil else { return nil }
        let query = url.query()
        switch (host, url.path()) {
        case ("agenda", ""): return query == nil ? .agenda : nil
        case ("route", "/active"): return query == nil ? .activeRoute : nil
        case ("departures", "/next"): return query == nil ? .nextDepartures : nil
        case ("train", "/search"):
            guard let query else { return .trainSearch(nil) }
            guard query.hasPrefix("number=") else { return nil }
            let number = String(query.dropFirst("number=".count))
            guard (1...12).contains(number.count), number.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { return nil }
            return .trainSearch(number)
        default: return nil
        }
    }

    static func open(_ link: AppLink) {
        pending = link
        NotificationCenter.default.post(name: notification, object: nil)
    }

    static func consume() -> AppLink? {
        defer { pending = nil }
        return pending
    }

    #if canImport(UIKit)
    /// The quick actions besides «Agenda» (`AgendaQuickAction`).
    static func handle(_ item: UIApplicationShortcutItem) -> Bool {
        switch item.type {
        case "it.binario.ios.active-route": open(.activeRoute)
        case "it.binario.ios.next-departures": open(.nextDepartures)
        case "it.binario.ios.train-search": open(.trainSearch(nil))
        default: return false
        }
        return true
    }
    #endif
}

/// Applies a link or quick action: the Agenda, the active route's board or «Treno».
struct AppLinksHandler: ViewModifier {
    @EnvironmentObject private var model: AppModel
    @State private var trainSearch: TrainSearchRequest?

    struct TrainSearchRequest: Identifiable {
        let id = UUID()
        let number: String
    }

    func body(content: Content) -> some View {
        content
            .onOpenURL { url in
                if let link = AppLinks.link(url) { apply(link) }
            }
            .onAppear { if let link = AppLinks.consume() { apply(link) } }
            .onReceive(NotificationCenter.default.publisher(for: AppLinks.notification)) { _ in
                if let link = AppLinks.consume() { apply(link) }
            }
            .sheet(item: $trainSearch) { request in
                NavigationStack { TrainNumberSearchView(initialNumber: request.number) }
            }
    }

    private func apply(_ link: AppLink) {
        switch link {
        case .agenda: model.openAgenda()
        case .activeRoute, .nextDepartures: model.selectedTab = .departures
        case let .trainSearch(number):
            model.selectedTab = .planner
            trainSearch = TrainSearchRequest(number: number ?? "")
        }
    }
}

// MARK: «Aggiungi al calendario»

/// The event of a train in the system's editor (Android's calendar intent): «Treno 2153 · Milano
/// Centrale → Pavia» at the scheduled departure, the operator and the note that the times are
/// scheduled. The editor runs outside the app: Binario asks no calendar permission and reads nothing.
struct CalendarEventButton: View {
    let service: TrainService
    let query: JourneyQuery
    @State private var editing = false

    var body: some View {
        if let start = departure {
            Button {
                editing = true
            } label: {
                Label("result.calendar.add", systemImage: "calendar.badge.plus")
            }
            .sheet(isPresented: $editing) {
                CalendarEventEditor(
                    title: PersonalTexts.format("result.share.train_title", service.number, query.origin.name, query.destination.name),
                    start: start,
                    location: query.origin.name,
                    notes: PersonalTexts.format("result.calendar.operator", service.operatorName) + "\n"
                        + NSLocalizedString("result.calendar.note", comment: "")
                )
                .ignoresSafeArea()
            }
        }
    }

    /// The scheduled departure on the search's day, in the timetable's zone.
    private var departure: Date? {
        guard let date = ItalianWallClock.date(of: query.departure),
              let time = PlannedLocalTime(hhmm: service.scheduledDeparture) else { return nil }
        return ItalianWallClock.instant(date: date, time: time)
    }
}

private struct CalendarEventEditor: UIViewControllerRepresentable {
    let title: String
    let start: Date
    let location: String
    let notes: String
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> EKEventEditViewController {
        let store = EKEventStore()
        let event = EKEvent(eventStore: store)
        event.title = title
        event.startDate = start
        event.endDate = start
        event.location = location
        event.notes = notes
        let controller = EKEventEditViewController()
        controller.eventStore = store
        controller.event = event
        controller.editViewDelegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: EKEventEditViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(dismiss: dismiss) }

    @MainActor
    final class Coordinator: NSObject, EKEventEditViewDelegate {
        let dismiss: DismissAction

        init(dismiss: DismissAction) {
            self.dismiss = dismiss
        }

        // The editor calls its delegate on the main thread, whatever the protocol's isolation.
        nonisolated func eventEditViewController(_ controller: EKEventEditViewController, didCompleteWith action: EKEventEditViewAction) {
            MainActor.assumeIsolated { dismiss() }
        }
    }
}
