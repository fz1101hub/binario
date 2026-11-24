import SwiftUI
import BinarioKit

/// The notification permission, asked only when the user turns a trip alert on (Android's
/// `rememberWithNotificationPermission`), through the Agenda's notification centre.
@MainActor
enum TripPermission {
    static func ensure() async -> Bool {
        if await TripNotifier.allowed() { return true }
        return await SystemAgendaNotificationCenter().requestPermission()
    }
}

/// «Notifiche in tempo reale» of a train detail (Android's `FollowedTripNotificationToggle`, `TripPositionEstimateToggle`,
/// the stop alarm of a stop, `TrainAlertToggle` and `ApproachAlertControl`), for a train with a number.
struct TripAlertsSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var trips: TripMonitor
    let service: TrainService
    let query: JourneyQuery
    /// The run the detail shows: its stops and times make the followed trip, the stop alarms and the stations.
    let liveRun: LiveRunStatus?

    @State private var feedback: String?

    var body: some View {
        if service.hasTrainNumber {
            Section {
                followRows
                stopAlarmRows
                bellRows
                approachRows
                if let feedback {
                    Text(verbatim: feedback)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("settings.alerts.live_title")
            }
        }
    }

    /// «REG 2153», as the detail names the train.
    private var label: String {
        [service.category, service.number].filter { !$0.isEmpty }.joined(separator: " ")
    }

    private var followsThis: Bool {
        guard let trip = trips.followedTrip, trip.legs.count == 1 else { return false }
        return TrainAlertWatch.key(trip.legs[0].number) == TrainAlertWatch.key(service.number)
    }

    private var candidate: FollowedTrip? {
        guard let liveRun else { return nil }
        return FollowedTrip.fromRun(liveRun, label: label, boardName: query.origin.name, boardCode: NetworkReliability.stationCode(query.origin),
                                    alightName: query.destination.name)
    }

    // MARK: Followed trip

    @ViewBuilder private var followRows: some View {
        if candidate != nil || followsThis {
            Toggle("result.trip.follow_toggle", isOn: Binding(get: { followsThis }, set: { enabled in
                Task { await setFollowing(enabled) }
            }))
            Text("result.trip.follow_detail")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text("result.trip.follow_ios_note")
                .font(.footnote)
                .foregroundStyle(.secondary)
            if followsThis {
                estimateRows
            }
        }
    }

    private func setFollowing(_ enabled: Bool) async {
        guard enabled else {
            await trips.stopFollowing()
            feedback = nil
            return
        }
        // The alerts of the trip (cancellation, platform, connection) are notifications: asked here, as on Android.
        guard await TripPermission.ensure() else {
            feedback = TripTexts.text("result.trip.stop_alarm_permission")
            return
        }
        guard let trip = candidate, FollowedTripPlanner.isUpcomingOrRunning(trip, now: Date()) else {
            feedback = TripTexts.text("result.trip.follow_unavailable")
            return
        }
        if let start = await trips.follow(trip) {
            feedback = TripTexts.format("result.trip.follow_pending", TripTime.clock(start))
        } else {
            feedback = nil
        }
    }

    @ViewBuilder private var estimateRows: some View {
        Toggle("result.trip.estimate_toggle", isOn: Binding(get: { trips.estimateEnabled }, set: { trips.setEstimateEnabled($0) }))
        Text("result.trip.estimate_detail")
            .font(.footnote)
            .foregroundStyle(.secondary)
        if let key = trips.estimateProblemKey {
            Text(LocalizedStringKey(key))
                .font(.footnote)
        } else if trips.estimateEnabled {
            let official = trips.officialDelayMinutes(service.number)
            if let line = TripTexts.position(trips.track?.estimate, status: trips.track?.status ?? .waiting,
                                             officialDelayMinutes: official, now: Date(), includeWaiting: true) {
                Text(verbatim: line)
                    .font(.footnote)
            }
            Text("result.trip.estimate_privacy")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: Stop alarm

    /// The future stops of the run, each with its expected time.
    private var futureStops: [LiveStopStatus] {
        let now = Date()
        return liveRun?.stops.filter { !$0.passed && !$0.skipped && $0.expected > now } ?? []
    }

    @ViewBuilder private var stopAlarmRows: some View {
        if let run = liveRun, !futureStops.isEmpty {
            Menu {
                ForEach(futureStops.indices, id: \.self) { index in
                    let stop = futureStops[index]
                    Button {
                        Task { await setStopAlarm(run: run, stop: stop) }
                    } label: {
                        Text(verbatim: "\(stop.stationName) · \(TripTime.clock(stop.expected))")
                    }
                    .accessibilityLabel(Text(verbatim: TripTexts.format("result.trip.stop_alarm_spoken", stop.stationName, TripTime.clock(stop.expected))))
                }
            } label: {
                Label("result.trip.stop_alarm_title", systemImage: "alarm")
            }
            Text("result.trip.stop_alarm_notice")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        ForEach(trips.stopAlarms.filter { TrainAlertWatch.key($0.trainNumber) == TrainAlertWatch.key(service.number) }, id: \.station) { plan in
            StopAlarmRow(plan: plan)
        }
    }

    private func setStopAlarm(run: LiveRunStatus, stop: LiveStopStatus) async {
        guard await TripPermission.ensure() else {
            feedback = TripTexts.text("result.trip.stop_alarm_permission")
            return
        }
        if let plan = await trips.setStopAlarm(run: run, stop: stop) {
            feedback = TripTexts.format("result.trip.stop_alarm_set", TripTime.clock(plan.triggerAt), plan.expectedTime)
        } else {
            feedback = TripTexts.text("result.trip.stop_alarm_unavailable")
        }
    }

    // MARK: New notices (the bell)

    /// For a favourite or followed train the per-train notice sources know (a Trenitalia train or a ViaggiaTreno run).
    private var bellContext: TrainNoticeContext? {
        guard model.isLiveDataActive, model.isFavourite(service, query: query) || followsThis else { return nil }
        let context = TrainNoticeContext(
            number: service.number, serviceDate: query.departure, originCode: liveRun?.originCode, operatorName: service.operatorName,
            stationNames: [], endpoints: [], nearTrenordNetwork: false
        )
        return context?.matchesTrenitaliaNumbers == true ? context : nil
    }

    @ViewBuilder private var bellRows: some View {
        if let context = bellContext {
            Toggle("result.trip.bell_toggle", isOn: Binding(get: { trips.isSubscribed(service.number) }, set: { enabled in
                Task { await setBell(enabled, context: context) }
            }))
            Text("result.trip.bell_detail")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private func setBell(_ enabled: Bool, context: TrainNoticeContext) async {
        guard enabled else {
            trips.unsubscribe(service.number)
            return
        }
        guard await TripPermission.ensure() else {
            feedback = TripTexts.text("settings.alerts.permission")
            return
        }
        trips.subscribe(TrainAlertSubscription(
            number: service.number, originCode: context.originCode, departure: service.scheduledDeparture, label: label, subscribedAt: Date()
        ))
        feedback = nil
    }

    // MARK: «Avvisami quando sta arrivando»

    /// The stations the alert can name: the run's stops with their scheduled times, else the boarding station.
    private var approachStops: [(name: String, time: String)] {
        if let stops = liveRun?.stops.filter({ !$0.skipped }), !stops.isEmpty {
            return stops.compactMap { stop -> (name: String, time: String)? in
                ItalianWallClock.time(of: stop.scheduled).map { (name: stop.stationName, time: $0.hhmm) }
            }
        }
        guard TripTime.parse(service.scheduledDeparture) != nil else { return [] }
        return [(name: query.origin.name, time: service.scheduledDeparture)]
    }

    @ViewBuilder private var approachRows: some View {
        if model.isFavourite(service, query: query), !approachStops.isEmpty {
            let current = trips.approachAlert(for: service.number)
            Toggle("result.trip.approach_toggle", isOn: Binding(get: { current != nil }, set: { enabled in
                Task { await setApproach(enabled) }
            }))
            Text("result.trip.approach_detail")
                .font(.footnote)
                .foregroundStyle(.secondary)
            if let current {
                Menu {
                    ForEach(approachStops.indices, id: \.self) { index in
                        let stop = approachStops[index]
                        Button {
                            Task { await saveApproach(station: stop, lead: current.leadMinutes) }
                        } label: {
                            Text(verbatim: "\(stop.name) · \(stop.time)")
                        }
                    }
                } label: {
                    Text(verbatim: TripTexts.format("result.trip.approach_station", current.station))
                }
                .accessibilityLabel(Text(verbatim: TripTexts.format("result.trip.approach_station_spoken", current.station)))
                Picker("result.trip.approach_lead", selection: Binding(get: { current.leadMinutes }, set: { lead in
                    let stop = approachStops.first { $0.name == current.station } ?? (name: current.station, time: current.stationTime)
                    Task { await saveApproach(station: stop, lead: lead) }
                })) {
                    ForEach(ApproachAlertPlanner.leadOptions, id: \.self) { lead in
                        Text(verbatim: TripTexts.minutes(lead)).tag(lead)
                    }
                }
                .pickerStyle(.segmented)
                Text(verbatim: TripTexts.format("result.trip.approach_summary", TripTexts.minutes(current.leadMinutes), current.station))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if ApproachAlertPlanner.quietOnTimetable(current) {
                    Text("result.trip.approach_quiet")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func setApproach(_ enabled: Bool) async {
        guard enabled else {
            trips.removeApproachAlert(service.number)
            return
        }
        guard await TripPermission.ensure() else {
            feedback = TripTexts.text("settings.alerts.permission")
            return
        }
        // By default the favourite's saved origin, as on Android.
        let stops = approachStops
        let origin = model.favouriteTrains.first { TrainAlertWatch.key($0.number) == TrainAlertWatch.key(service.number) }?.origin.name
        let stop = stops.first { origin != nil && TripStationNames.key($0.name) == TripStationNames.key(origin ?? "") } ?? stops[0]
        await saveApproach(station: stop, lead: ApproachAlertPlanner.defaultLead)
    }

    private func saveApproach(station: (name: String, time: String), lead: Int) async {
        let departure = TripTime.parse(service.scheduledDeparture) != nil ? service.scheduledDeparture : (approachStops.first?.time ?? "")
        await trips.saveApproachAlert(ApproachAlert(
            number: service.number.trimmingCharacters(in: .whitespaces), label: label, station: station.name, stationTime: station.time,
            departure: departure, leadMinutes: lead, createdAt: Date()
        ))
        feedback = nil
    }
}

/// One active stop alarm with its cancel button (Android's «Sveglie fermata attive»).
private struct StopAlarmRow: View {
    @EnvironmentObject private var trips: TripMonitor
    let plan: StopAlarmPlan

    var body: some View {
        HStack {
            Text(verbatim: TripTexts.format("settings.alerts.stop_alarm_row", plan.station, plan.trainNumber, plan.expectedTime))
                .font(.subheadline)
            Spacer()
            Button {
                trips.cancelStopAlarm(plan)
            } label: {
                Image(systemName: "xmark.circle")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(Text(verbatim: TripTexts.format("settings.alerts.stop_alarm_cancel", plan.station)))
        }
    }
}

/// «Notifiche e sveglie» of the settings: the switch of the train notices, the active stop alarms, the commuter
/// alerts, the quiet hours and what iOS lets these alerts do (Android's `LiveUpdatesSettingsPanel`).
struct TripAlertsSettingsSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var trips: TripMonitor
    @State private var editing = false
    @State private var feedback: String?

    var body: some View {
        Section {
            Toggle("settings.alerts.train_title", isOn: Binding(get: { trips.trainAlertsEnabled }, set: { enabled in
                Task { await setTrainAlerts(enabled) }
            }))
            Text("settings.alerts.train_detail")
                .font(.footnote)
                .foregroundStyle(.secondary)
            if !trips.stopAlarms.isEmpty {
                Text("settings.alerts.stop_alarms_title")
                    .font(.subheadline.weight(.semibold))
                ForEach(trips.stopAlarms, id: \.triggerAt) { plan in
                    StopAlarmRow(plan: plan)
                }
            }
            Text("settings.alerts.commuter_title")
                .font(.subheadline.weight(.semibold))
            Text("settings.alerts.commuter_detail")
                .font(.footnote)
                .foregroundStyle(.secondary)
            ForEach(trips.commuterAlerts) { alert in
                CommuterAlertRow(alert: alert)
            }
            Button("settings.alerts.commuter_create") { editing = true }
            if let feedback {
                Text(LocalizedStringKey(feedback))
                    .font(.footnote)
            }
            Text("settings.alerts.ios_background")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Text("settings.alerts.section")
        }
        .sheet(isPresented: $editing) {
            NavigationStack {
                CommuterAlertEditor { key in feedback = key }
            }
            .environmentObject(model)
            .environmentObject(trips)
        }
    }

    private func setTrainAlerts(_ enabled: Bool) async {
        guard enabled else {
            trips.setTrainAlertsEnabled(false)
            return
        }
        guard await TripPermission.ensure() else {
            feedback = "settings.alerts.permission"
            return
        }
        trips.setTrainAlertsEnabled(true)
    }
}

/// «Milano Centrale → Pavia, fermata Pavia alle 08:15, avviso 10 min prima, Lun–ven, fascia silenziosa 22:00–07:00.»
private struct CommuterAlertRow: View {
    @EnvironmentObject private var trips: TripMonitor
    let alert: CommuterAlert

    var body: some View {
        HStack {
            Toggle(isOn: Binding(get: { alert.enabled }, set: { enabled in
                Task { await trips.setCommuterAlertEnabled(alert.id, enabled: enabled) }
            })) {
                Text(verbatim: CommuterAlertTexts.summary(alert))
                    .font(.subheadline)
            }
            Button {
                trips.removeCommuterAlert(alert.id)
            } label: {
                Image(systemName: "trash")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(Text(verbatim: TripTexts.format("settings.alerts.commuter_remove", alert.routeLabel)))
        }
    }
}

enum CommuterAlertTexts {
    static func repeatLabel(_ mode: CommuterAlertRepeat) -> String {
        switch mode {
        case .once: return TripTexts.text("settings.alerts.repeat_once")
        case .weekdays: return TripTexts.text("settings.alerts.repeat_weekdays")
        case .daily: return TripTexts.text("settings.alerts.repeat_daily")
        }
    }

    static func summary(_ alert: CommuterAlert) -> String {
        TripTexts.format(
            "settings.alerts.commuter_summary", alert.routeLabel, alert.stationName, alert.time, TripTexts.minutes(alert.leadMinutes),
            repeatLabel(alert.repeatMode), alert.quietHoursEnabled ? TripTexts.text("settings.alerts.commuter_summary_quiet") : "",
            alert.enabled ? "" : TripTexts.text("settings.alerts.commuter_summary_disabled")
        )
    }
}

/// Android's commuter alert sheet: a saved route, its station, the train's time, the lead, the repetition and the
/// quiet hours.
private struct CommuterAlertEditor: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var trips: TripMonitor
    @Environment(\.dismiss) private var dismiss
    let saved: (String) -> Void

    @State private var routeID = ""
    @State private var useDestination = false
    @State private var time = Date()
    @State private var lead = 10
    @State private var repeatMode = CommuterAlertRepeat.weekdays
    @State private var quietHours = true

    var body: some View {
        Form {
            if model.savedRoutes.isEmpty {
                Text("settings.alerts.commuter_route_required")
            } else {
                Picker(selection: $routeID) {
                    ForEach(model.savedRoutes) { route in
                        Text(verbatim: "\(route.origin.name) → \(route.destination.name)").tag(route.id)
                    }
                } label: {
                    Text("settings.alerts.commuter_sheet_title")
                }
                if let route {
                    Picker("settings.alerts.commuter_station", selection: $useDestination) {
                        Text(verbatim: route.origin.name).tag(false)
                        Text(verbatim: route.destination.name).tag(true)
                    }
                }
                DatePicker(selection: $time, displayedComponents: .hourAndMinute) {
                    Text(verbatim: TripTexts.format("settings.alerts.commuter_time", hhmm))
                }
                .environment(\.timeZone, ItalianWallClock.timeZone)
                Picker("settings.alerts.commuter_lead", selection: $lead) {
                    ForEach(CommuterAlert.leadOptions, id: \.self) { minutes in
                        Text(verbatim: TripTexts.minutes(minutes)).tag(minutes)
                    }
                }
                Picker("settings.alerts.commuter_repeat", selection: $repeatMode) {
                    ForEach(CommuterAlertRepeat.allCases, id: \.self) { mode in
                        Text(verbatim: CommuterAlertTexts.repeatLabel(mode)).tag(mode)
                    }
                }
                Toggle("settings.alerts.commuter_quiet", isOn: $quietHours)
                Button("settings.alerts.commuter_save") {
                    Task { await save() }
                }
                .disabled(route == nil)
            }
        }
        .navigationTitle(Text("settings.alerts.commuter_sheet_title"))
        .onAppear {
            if routeID.isEmpty { routeID = model.savedRoutes.first?.id ?? "" }
        }
    }

    private var route: SavedRoute? { model.savedRoutes.first { $0.id == routeID } }

    private var hhmm: String { ItalianWallClock.time(of: time)?.hhmm ?? "08:00" }

    private func save() async {
        guard let route else { return }
        guard await TripPermission.ensure() else {
            saved("settings.alerts.permission")
            dismiss()
            return
        }
        let alert = CommuterAlert(
            id: UUID().uuidString, routeLabel: "\(route.origin.name) → \(route.destination.name)",
            stationName: useDestination ? route.destination.name : route.origin.name, time: hhmm, leadMinutes: lead,
            repeatMode: repeatMode, quietHoursEnabled: quietHours
        )
        saved(await trips.saveCommuterAlert(alert))
        dismiss()
    }
}
