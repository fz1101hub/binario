import Foundation
import BinarioKit
#if canImport(UIKit)
import UIKit
#endif
#if canImport(ActivityKit)
import ActivityKit
#endif

/// Android's trip notifications and alarms on the iPhone (notifications/ in the Android app): the followed trip as a
/// Live Activity with «Aggiorna» and «Interrompi», the stop alarms, «Avvisami quando sta arrivando», the new notices of
/// the trains with the bell, the commuter alerts with the train's status, and the position during the trip.
///
/// Android runs these checks with WorkManager. Here they run every 30 seconds while the app is in the foreground and,
/// when iOS grants it, in the app's one background refresh task (EveningAlerts.swift), with the same rules
/// (BinarioKit's TripAlerts.swift). The live sources receive a train number and a day, as when a detail is opened,
/// and only with «Dati in tempo reale» on. Everything is kept on this iPhone (TripAlertsStore); positions are never kept.
@MainActor
final class TripMonitor: ObservableObject {
    static let shared = TripMonitor()

    @Published private(set) var followedTrip: FollowedTrip?
    @Published private(set) var stopAlarms: [StopAlarmPlan] = []
    @Published private(set) var approachAlerts: [ApproachAlert] = []
    @Published private(set) var trainAlertsEnabled = false
    @Published private(set) var trainAlertSubscriptions: [TrainAlertSubscription] = []
    @Published private(set) var commuterAlerts: [CommuterAlert] = []
    /// When the sources last answered for the followed trip: Per te's panel reads its stops again.
    @Published private(set) var followedAnsweredAt: Date?
    /// «Stima dalla tua posizione» for the followed train, in memory only.
    @Published private(set) var estimateEnabled = false
    @Published private(set) var track: GpsTrainTrack?
    @Published private(set) var estimateProblemKey: String?

    private weak var model: AppModel?
    private let store = TripAlertsStore()
    private let location: TripLocationSession
    /// The runs the last refresh read, in memory only: the position repaints the Live Activity without asking again.
    private var lastRuns: (tripID: String, runs: [LiveRunStatus?], at: Date)?
    private var loop: Task<Void, Never>?
    private var busy = false
    private var isActive = false
    private var estimateStartedAt: Date?
    private var lastRepaint: Date?

    private init() {
        location = TripLocationSession()
        followedTrip = store.followedTrip
        stopAlarms = store.stopAlarms
        approachAlerts = store.approachAlerts
        trainAlertsEnabled = store.trainAlertsEnabled
        trainAlertSubscriptions = store.trainAlertSubscriptions
        commuterAlerts = store.commuterAlerts
        TripActivityActions.handler = { action, tripID in
            await TripMonitor.shared.handle(action, tripID: tripID)
        }
        location.onTrack = { track in
            TripMonitor.shared.positionChanged(track)
        }
        #if canImport(UIKit)
        let center = NotificationCenter.default
        _ = center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { TripMonitor.shared.didBecomeActive() }
        }
        _ = center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { TripMonitor.shared.didEnterBackground() }
        }
        #endif
    }

    /// Called once at launch (AppModel.bootstrap), also for a launch in the background.
    func attach(_ model: AppModel) {
        self.model = model
    }

    /// The background refresh task (EveningAlerts.run): every check that is due, once.
    static func runInBackground(now: Date) async {
        await shared.runChecks(now: now)
    }

    // MARK: Foreground

    private func didBecomeActive() {
        isActive = true
        loop?.cancel()
        loop = Task { [weak self] in
            while !Task.isCancelled {
                await self?.runChecks(now: Date())
                try? await Task.sleep(nanoseconds: 30 * 1_000_000_000)
            }
        }
        startEstimateIfPossible()
    }

    private func didEnterBackground() {
        isActive = false
        loop?.cancel()
        loop = nil
        // Without a background location mode iOS stops the updates anyway: the session pauses until the app is back.
        location.stop()
    }

    private func runChecks(now: Date) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        if let trip = followedTrip, (store.followedState.nextRefreshAt ?? .distantPast) <= now {
            await refreshFollowed(trip, now: now)
        }
        await checkApproachAlerts(now: now)
        await checkTrainAlerts(now: now)
        await planCommuterAlerts(now: now)
        cleanStopAlarms(now: now)
    }

    private var liveData: (any LiveRailProviding)? { model?.tripLiveData }

    private func handle(_ action: TripActivityAction, tripID: String) async {
        guard followedTrip?.id == tripID else {
            await endActivities()
            return
        }
        switch action {
        case .refresh:
            if let trip = followedTrip { await refreshFollowed(trip, now: Date()) }
        case .stop:
            await stopFollowing()
        }
    }

    // MARK: Followed trip

    /// Starts following `trip`, replacing a previous one; nil when it cannot be shown, else the time it starts
    /// refreshing when that is still ahead. The Live Activity starts now: iOS lets only the open app start one.
    func follow(_ trip: FollowedTrip) async -> Date? {
        await stopFollowing()
        store.followedTrip = trip
        store.followedState = TripAlertsStore.FollowedState()
        followedTrip = trip
        let now = Date()
        let start = FollowedTripPlanner.windowStart(trip)
        let view = FollowedTripStatus.view(trip, runs: [], now: now)
        let content = TripTexts.content(trip, view: view, answered: true, updatedAt: now, now: now)
        startActivity(trip, content: content.state)
        await refreshFollowed(trip, now: now)
        rescheduleTrainAlerts()
        return start > now ? start : nil
    }

    /// Ends the Live Activity and every refresh: «Interrompi», the toggle, or the end of the trip.
    func stopFollowing() async {
        store.followedTrip = nil
        store.followedState = TripAlertsStore.FollowedState()
        followedTrip = nil
        lastRuns = nil
        stopEstimate()
        await endActivities()
        rescheduleTrainAlerts()
    }

    /// The run the sources last gave for a train of the followed trip, for Per te's «Viaggio e monitoraggi».
    func followedRun(legIndex: Int) -> LiveRunStatus? {
        guard let trip = followedTrip, let last = lastRuns, last.tripID == trip.id, last.runs.indices.contains(legIndex) else { return nil }
        return last.runs[legIndex]
    }

    func follows(_ number: String) -> Bool {
        followedTrip?.trainNumbers.contains(TrainAlertWatch.key(number)) == true
    }

    private func refreshFollowed(_ trip: FollowedTrip, now: Date) async {
        var state = store.followedState
        switch FollowedTripPlanner.step(trip, now: now, lastDelayMinutes: state.lastDelayMinutes) {
        case .finish:
            await stopFollowing()
            return
        case let .waitUntil(at):
            state.nextRefreshAt = at
            store.followedState = state
            return
        case .refresh:
            break
        }
        let runs = await lookUp(trip, now: now)
        // A stop requested while the trains were being looked up wins over this refresh.
        guard followedTrip?.id == trip.id else { return }
        let answered = runs.contains { $0 != nil }
        if answered {
            lastRuns = (trip.id, runs, now)
            followedAnsweredAt = now
        }
        replanStopAlarms(runs: runs.compactMap { $0 }, now: now)
        let delay = FollowedTripStatus.arrivalDelayMinutes(trip, runs: runs)
        let end = FollowedTripPlanner.windowEnd(trip, lastDelayMinutes: delay ?? state.lastDelayMinutes)
        let view = FollowedTripStatus.view(trip, runs: runs, now: now, position: track)
        let content: TripActivityContent
        if !answered, let title = state.lastTitle, let updated = state.lastUpdatedAt {
            // Nothing answered: the last status stays, saying how old it is.
            let stale = TripTexts.format("result.trip.stale", TripTime.clock(updated))
            content = TripActivityContent(title: title, line: stale, details: [stale] + state.lastLines, compact: "", warning: false)
        } else {
            let made = TripTexts.content(trip, view: view, answered: answered, updatedAt: now, now: now)
            content = made.state
            if answered {
                if let delay { state.lastDelayMinutes = delay }
                state.lastTitle = made.state.title
                state.lastLines = made.recorded
                state.lastUpdatedAt = now
            }
        }
        await updateActivity(trip, content: content, staleDate: end)
        if answered { await notifyChanges(trip, view: view, runs: runs, state: &state, now: now) }
        switch FollowedTripPlanner.step(trip, now: now, lastDelayMinutes: delay ?? state.lastDelayMinutes) {
        case let .refresh(next):
            state.nextRefreshAt = FollowedTripPlanner.nextRefreshAt(regular: next, now: now, countdown: answered ? view.countdown : nil)
        case let .waitUntil(at):
            state.nextRefreshAt = at
        case .finish:
            state.nextRefreshAt = now
        }
        store.followedState = state
        startEstimateIfPossible()
    }

    /// Only the trains of the trip that run now or soon are asked, at most two (Android's lookahead and lookback).
    private func lookUp(_ trip: FollowedTrip, now: Date) async -> [LiveRunStatus?] {
        guard let liveData else { return trip.legs.map { _ in nil } }
        var relevant = trip.legs.indices.filter { index in
            let leg = trip.legs[index]
            return leg.departure <= now.addingTimeInterval(2 * 3_600) && leg.arrival.addingTimeInterval(3 * 3_600) >= now
        }
        if relevant.isEmpty {
            relevant = [trip.legs.indices.first { trip.legs[$0].arrival > now } ?? trip.legs.count - 1]
        }
        var runs: [LiveRunStatus?] = trip.legs.map { _ in nil }
        for index in relevant.prefix(2) {
            let leg = trip.legs[index]
            let request = LiveTrainStatusRequest(number: NetworkReliability.numberKey(leg.number), serviceDate: leg.departure, stationCode: leg.boardCode)
            if case let .available(lookup) = await liveData.trainStatus(request) {
                runs[index] = FollowedTripStatus.pick(lookup.runs, leg: leg)
            }
        }
        return runs
    }

    /// A cancellation, a platform change or a connection at risk: one notification with sound (silent 22–7).
    private func notifyChanges(_ trip: FollowedTrip, view: FollowedTripView, runs: [LiveRunStatus?], state: inout TripAlertsStore.FollowedState, now: Date) async {
        let boarding = FollowedTripAlerts.boardingPlatform(trip, view: view, runs: runs)
        let changes = FollowedTripAlerts.changes(trip, view: view, runs: runs, previousBoarding: state.lastBoarding, notified: Set(state.alertedKeys))
        state.lastBoarding = boarding
        guard !changes.isEmpty, await TripNotifier.allowed() else { return }
        let lines = changes.map { change -> (String, String) in
            let leg = trip.legs[change.legIndex]
            let label = leg.label.isEmpty ? leg.number : leg.label
            switch change {
            case .cancelled:
                return (TripTexts.format("result.trip.title_cancelled", label), trip.title)
            case let .platformChanged(_, station, platform, previous, _):
                return (TripTexts.format("result.trip.platform_changed", platform, previous), "\(label) · \(station)")
            case let .connectionAtRisk(_, level, _):
                return (TripTexts.transferRisk(level), TripTexts.format("result.trip.transfer", view.transferStation ?? leg.alightStation, view.nextLegLabel ?? ""))
            }
        }
        let body = ([lines[0].1] + lines.dropFirst().map { "\($0.0) · \($0.1)" }).joined(separator: "\n")
        if await TripNotifier.post(id: TripNotifier.followedAlertID, title: lines[0].0, body: body, sound: !TripQuietHours.isQuiet(at: now)) {
            state.alertedKeys += changes.map(\.key)
        }
    }

    // MARK: Live Activity

    private func startActivity(_ trip: FollowedTrip, content: TripActivityContent) {
        #if canImport(ActivityKit)
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let attributes = TripActivityAttributes(
            tripID: trip.id, refreshLabel: TripTexts.text("action.refresh"), stopLabel: TripTexts.text("result.trip.stop")
        )
        let staleDate = FollowedTripPlanner.windowEnd(trip, lastDelayMinutes: nil)
        _ = try? Activity.request(attributes: attributes, content: ActivityContent(state: Self.state(content), staleDate: staleDate), pushType: nil)
        #endif
    }

    private func updateActivity(_ trip: FollowedTrip, content: TripActivityContent, staleDate: Date) async {
        #if canImport(ActivityKit)
        for activity in Activity<TripActivityAttributes>.activities where activity.attributes.tripID == trip.id {
            await activity.update(ActivityContent(state: Self.state(content), staleDate: staleDate))
        }
        #endif
    }

    private func endActivities() async {
        #if canImport(ActivityKit)
        for activity in Activity<TripActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
        #endif
    }

    #if canImport(ActivityKit)
    private static func state(_ content: TripActivityContent) -> TripActivityAttributes.ContentState {
        TripActivityAttributes.ContentState(
            title: content.title, line: content.line, details: content.details, compact: content.compact, warning: content.warning
        )
    }
    #endif

    // MARK: Position during the trip

    /// «Stima dalla tua posizione»: precise when-in-use location while the app is open and the trip is followed.
    func setEstimateEnabled(_ enabled: Bool) {
        estimateEnabled = enabled
        estimateProblemKey = nil
        if enabled { startEstimateIfPossible() } else { stopEstimate() }
    }

    private func stopEstimate() {
        estimateEnabled = false
        estimateStartedAt = nil
        location.stop()
        track = nil
    }

    private func startEstimateIfPossible(now: Date = Date()) {
        guard estimateEnabled, isActive, !location.isRunning, let trip = followedTrip, let runs = lastRuns, runs.tripID == trip.id else { return }
        let lives = trip.legs.indices.map { FollowedTripStatus.live(trip.legs[$0], run: runs.runs.indices.contains($0) ? runs.runs[$0] : nil) }
        let index = FollowedTripStatus.currentLegIndex(trip, lives: lives, now: now)
        let leg = trip.legs[index]
        guard GpsTrainTracking.canStartEstimate(departure: leg.departure, arrival: leg.arrival, officialDelayMinutes: runs.runs[index]?.delayMinutes, now: now) else {
            estimateProblemKey = "result.trip.estimate_unavailable_time"
            return
        }
        guard let run = runs.runs[index], let model,
              let route = GpsTrainRoute.build(run, boardName: leg.boardStation, alightName: leg.alightStation, locate: { model.tripCoordinates($0) }) else {
            estimateProblemKey = "result.trip.estimate_unavailable_route"
            return
        }
        estimateStartedAt = estimateStartedAt ?? now
        switch location.start(route: route) {
        case .started:
            estimateProblemKey = nil
        case .preciseRequired:
            estimateProblemKey = "result.trip.estimate_precise"
        case .denied:
            estimateProblemKey = "result.trip.estimate_precise"
            estimateEnabled = false
        }
    }

    private func positionChanged(_ track: GpsTrainTrack) {
        self.track = track
        guard let trip = followedTrip else { return }
        let now = Date()
        let leg = trip.legs.first { track.isFor($0.number) } ?? trip.legs[0]
        // It stops ten minutes after the arrival.
        if let started = estimateStartedAt,
           now >= GpsTrainTracking.endsAt(arrival: leg.arrival, startedAt: started, track: track, officialDelayMinutes: nil) {
            stopEstimate()
            return
        }
        // Repaint from the runs already read, at most every 15 seconds: no source is asked.
        guard let runs = lastRuns, runs.tripID == trip.id, now.timeIntervalSince(lastRepaint ?? .distantPast) >= 15 else { return }
        lastRepaint = now
        let view = FollowedTripStatus.view(trip, runs: runs.runs, now: now, position: track)
        let content = TripTexts.content(trip, view: view, answered: true, updatedAt: runs.at, now: now)
        let end = FollowedTripPlanner.windowEnd(trip, lastDelayMinutes: store.followedState.lastDelayMinutes)
        Task { await self.updateActivity(trip, content: content.state, staleDate: end) }
    }

    /// The official delay of the followed train the last refresh read, for «(fonte ufficiale: +4 min)».
    func officialDelayMinutes(_ number: String) -> Int? {
        guard let trip = followedTrip, let runs = lastRuns, runs.tripID == trip.id,
              let index = trip.legs.firstIndex(where: { TrainAlertWatch.key($0.number) == TrainAlertWatch.key(number) }),
              runs.runs.indices.contains(index), let run = runs.runs[index], run.operationalStatusVerified, run.state != .cancelled else { return nil }
        return max(run.delayMinutes ?? 0, 0)
    }

    // MARK: Stop alarm

    /// «Sveglia fermata» on a future stop of a run: a local notification five minutes before its expected time.
    func setStopAlarm(run: LiveRunStatus, stop: LiveStopStatus, now: Date = Date()) async -> StopAlarmPlan? {
        guard let plan = StopAlarmPlanner.plan(trainNumber: run.number, destination: run.destinationName, station: stop.stationName,
                                               expectedTime: TripTime.clock(stop.expected), expectedAt: stop.expected, now: now) else { return nil }
        let kept = StopAlarmPlanner.upsert(store.stopAlarms, plan, now: now)
        let dropped = store.stopAlarms.filter { old in !kept.contains { StopAlarmPlanner.key($0.trainNumber, $0.station) == StopAlarmPlanner.key(old.trainNumber, old.station) } }
        TripNotifier.remove(dropped.map(TripNotifier.stopAlarmID))
        store.stopAlarms = kept
        stopAlarms = kept
        await postStopAlarm(plan, now: now)
        return plan
    }

    func cancelStopAlarm(_ plan: StopAlarmPlan) {
        TripNotifier.remove([TripNotifier.stopAlarmID(plan)])
        let key = StopAlarmPlanner.key(plan.trainNumber, plan.station)
        store.stopAlarms = store.stopAlarms.filter { StopAlarmPlanner.key($0.trainNumber, $0.station) != key }
        stopAlarms = store.stopAlarms
    }

    private func postStopAlarm(_ plan: StopAlarmPlan, now: Date) async {
        await TripNotifier.post(
            id: TripNotifier.stopAlarmID(plan),
            title: TripTexts.format("notices.alerts.stop_alarm_title", plan.station),
            body: TripTexts.format("notices.alerts.stop_alarm_body", plan.trainNumber, plan.destination, plan.station, plan.expectedTime),
            at: plan.triggerAt, now: now
        )
    }

    /// A followed-trip refresh read the trains: a stop alarm on one of them follows its expected time.
    private func replanStopAlarms(runs: [LiveRunStatus], now: Date) {
        let moved = StopAlarmPlanner.replanned(store.stopAlarms, runs: runs, now: now)
        guard !moved.isEmpty else { return }
        var plans = store.stopAlarms
        for plan in moved { plans = StopAlarmPlanner.upsert(plans, plan, now: now) }
        store.stopAlarms = plans
        stopAlarms = plans
        Task { for plan in moved { await self.postStopAlarm(plan, now: now) } }
    }

    private func cleanStopAlarms(now: Date) {
        let kept = store.stopAlarms.filter { !StopAlarmPlanner.isOver($0, now: now) }
        guard kept.count != store.stopAlarms.count else { return }
        store.stopAlarms = kept
        stopAlarms = kept
    }

    // MARK: «Avvisami quando sta arrivando»

    func approachAlert(for number: String) -> ApproachAlert? {
        approachAlerts.first { $0.key == TrainAlertWatch.key(number) }
    }

    /// Saves the alert of a favourite train; a changed station or lead starts the day afresh.
    func saveApproachAlert(_ alert: ApproachAlert) async {
        var alerts = [alert] + store.approachAlerts.filter { $0.key != alert.key }
        alerts = Array(alerts.prefix(ApproachAlertPlanner.maximumAlerts))
        store.approachAlerts = alerts
        var states = store.approachStates
        states[alert.key] = nil
        store.approachStates = states
        approachAlerts = alerts
        TripNotifier.remove([TripNotifier.approachID(alert.key)])
        await checkApproachAlerts(now: Date())
    }

    func removeApproachAlert(_ number: String) {
        let key = TrainAlertWatch.key(number)
        store.approachAlerts = store.approachAlerts.filter { $0.key != key }
        var states = store.approachStates
        states[key] = nil
        store.approachStates = states
        approachAlerts = store.approachAlerts
        TripNotifier.remove([TripNotifier.approachID(key)])
    }

    private func checkApproachAlerts(now: Date) async {
        let favourites = model?.favouriteTrains.map(\.number) ?? []
        let active = ApproachAlertPlanner.active(store.approachAlerts, favouriteNumbers: favourites)
        if model != nil, active.count != store.approachAlerts.count {
            // Forgets the alerts of trains that are no longer favourites.
            let removed = store.approachAlerts.filter { alert in !active.contains { $0.key == alert.key } }
            TripNotifier.remove(removed.map { TripNotifier.approachID($0.key) })
            store.approachAlerts = active
            approachAlerts = active
        }
        guard !active.isEmpty, await TripNotifier.allowed(), let today = ItalianWallClock.date(of: now) else { return }
        var states = store.approachStates
        for alert in active {
            let date = ApproachAlertPlanner.serviceDate(alert, day: today)
            var state = states[alert.key] ?? TripAlertsStore.ApproachState()
            if state.date != date.iso {
                state = TripAlertsStore.ApproachState(date: date.iso, doneOn: state.doneOn, notifiedOn: state.notifiedOn)
            }
            // The pending notification planned for this day has been shown by the system meanwhile.
            if let alarm = state.alarmAt, alarm <= now, state.notifiedOn != date.iso { state.notifiedOn = date.iso }
            let doneOn = state.doneOn.flatMap { PlannedLocalDate(iso: $0) }
            let notifiedOn = state.notifiedOn.flatMap { PlannedLocalDate(iso: $0) }
            if let window = ApproachAlertPlanner.window(alert, date: date), doneOn != date, notifiedOn != date,
               now >= window.lowerBound.addingTimeInterval(-ApproachAlertPlanner.earlyTolerance), now <= window.upperBound,
               (state.nextCheckAt ?? .distantPast) <= now {
                let result: LiveTrainStatusResult?
                if let liveData, let noon = PlannedLocalTime(hour: 12, minute: 0), let day = TripTime.instant(date, noon) {
                    result = await liveData.trainStatus(LiveTrainStatusRequest(number: NetworkReliability.numberKey(alert.number), serviceDate: day))
                } else {
                    result = nil
                }
                let lookup = ApproachAlertPlanner.lookup(result, alert: alert, date: date)
                let observation = ApproachAlertPlanner.observationAfter(lookup, last: state.observation)
                let decided = Date()
                switch ApproachAlertPlanner.decide(alert, date: date, lookup: lookup, last: state.observation, now: decided) {
                case let .notify(minutes):
                    await notifyApproach(alert, observation: observation, minutes: minutes, at: nil, now: decided)
                    state.notifiedOn = date.iso
                    state.observation = observation
                case .done:
                    state.doneOn = date.iso
                    state.observation = observation
                case let .waitUntil(at):
                    state.observation = observation
                    state.nextCheckAt = at
                }
            }
            // The pending notification at the moment to notify, from the last observation or the timetable: iOS
            // shows it even if no later check runs (Android's wake-up alarm).
            let fallback = ApproachAlertPlanner.scheduledAt(alert, date: date).map {
                ApproachObservation(expectedAt: $0, delayMinutes: nil, platform: nil, passed: false, cancelled: false)
            }
            let observation = state.observation ?? fallback
            let alarm = ApproachAlertPlanner.alarmAt(
                alert, doneOn: state.doneOn.flatMap { PlannedLocalDate(iso: $0) }, notifiedOn: state.notifiedOn.flatMap { PlannedLocalDate(iso: $0) },
                observation: observation, now: now
            )
            if let alarm, let observation {
                if alarm != state.alarmAt {
                    let minutes = ApproachAlertPlanner.minutesUntil(now: alarm, expected: observation.expectedAt)
                    await notifyApproach(alert, observation: observation, minutes: minutes, at: alarm, now: now)
                }
            } else {
                TripNotifier.remove([TripNotifier.approachID(alert.key)])
            }
            state.alarmAt = alarm
            states[alert.key] = state
        }
        store.approachStates = states
    }

    private func notifyApproach(_ alert: ApproachAlert, observation: ApproachObservation?, minutes: Int, at: Date?, now: Date) async {
        let label = alert.label.isEmpty ? alert.number : alert.label
        let title = minutes <= 0
            ? TripTexts.format("notices.alerts.approach_title_now", label, alert.station)
            : TripTexts.format("notices.alerts.approach_title", label, alert.station, TripTexts.minutes(minutes))
        let body: String
        if let observation, observation.live {
            body = TripTexts.status(CommuterLiveStatus(delayMinutes: observation.delayMinutes ?? 0, platform: observation.platform, cancelled: false))
        } else {
            body = TripTexts.text("result.trip.scheduled_only")
        }
        await TripNotifier.post(id: TripNotifier.approachID(alert.key), title: title, body: body, at: at, now: now)
    }

    // MARK: New notices of the trains with the bell

    func isSubscribed(_ number: String) -> Bool {
        trainAlertsEnabled && trainAlertSubscriptions.contains { $0.key == TrainAlertWatch.key(number) }
    }

    func subscribe(_ subscription: TrainAlertSubscription) {
        let subscriptions = [subscription] + store.trainAlertSubscriptions.filter { $0.key != subscription.key }
        store.trainAlertSubscriptions = Array(subscriptions.prefix(TrainAlertWatch.maximumSubscriptions))
        trainAlertSubscriptions = store.trainAlertSubscriptions
        setTrainAlertsEnabled(true)
    }

    func unsubscribe(_ number: String) {
        let key = TrainAlertWatch.key(number)
        store.trainAlertSubscriptions = store.trainAlertSubscriptions.filter { $0.key != key }
        trainAlertSubscriptions = store.trainAlertSubscriptions
    }

    func setTrainAlertsEnabled(_ enabled: Bool) {
        store.trainAlertsEnabled = enabled
        trainAlertsEnabled = enabled
    }

    /// The bells of trains that are no longer favourite or followed are removed, whatever made them leave.
    private func rescheduleTrainAlerts(now: Date = Date()) {
        guard let model else { return }
        let followed = followedTrip?.legs.map(\.number) ?? []
        let keys = TrainAlertWatch.watchedKeys(favourites: model.favouriteTrains.map(\.number), followed: followed)
        let previous = store.trainAlertsWatched.map(Set.init)
        let pruned = TrainAlertWatch.prune(store.trainAlertSubscriptions, previouslyWatched: previous, watched: keys, now: now)
        if pruned != store.trainAlertSubscriptions {
            store.trainAlertSubscriptions = pruned
            trainAlertSubscriptions = pruned
        }
        store.trainAlertsWatched = keys.sorted()
    }

    private func watchedTrains() -> ([TrainAlertSubscription], [FollowedTrainTimes]) {
        rescheduleTrainAlerts()
        let followed = followedTrip?.legs.map { FollowedTrainTimes(number: $0.number, departure: $0.departure, arrival: $0.arrival) } ?? []
        let keys = Set(store.trainAlertsWatched ?? [])
        return (store.trainAlertSubscriptions.filter { keys.contains($0.key) }, followed)
    }

    private func checkTrainAlerts(now: Date) async {
        guard store.trainAlertsEnabled, let liveData, model != nil else { return }
        let (subscriptions, followed) = watchedTrains()
        guard let next = TrainAlertWatch.nextRun(subscriptions, followed: followed, now: now, lastCheckAt: store.trainAlertsLastCheck),
              next <= now else { return }
        let due = TrainAlertWatch.due(subscriptions, followed: followed, now: now)
        guard !due.isEmpty, await TripNotifier.allowed(), let today = ItalianWallClock.date(of: now) else { return }
        store.trainAlertsLastCheck = now
        let language = TrainNoticeTexts.language
        for subscription in due {
            let request = LiveTrainNoticesRequest(number: NetworkReliability.numberKey(subscription.number), serviceDate: now, originCode: subscription.originCode)
            guard case let .available(found) = await liveData.trainNotices(request) else { continue }
            let watched = found.notices.map { WatchedNotice(id: $0.id, text: $0.text(for: language), publishedAt: $0.publishedAt) }
            let fresh = TrainAlertWatch.newNotices(watched, seen: store.trainAlertsSeen, subscribedAt: subscription.subscribedAt)
            if !fresh.isEmpty {
                let label = subscription.label.isEmpty ? subscription.number : subscription.label
                let title = String.localizedStringWithFormat(NSLocalizedString("notices.alerts.train_title", comment: ""), fresh.count, label)
                await TripNotifier.post(id: TripNotifier.trainAlertID(subscription.key), title: title, body: fresh.map(\.text).joined(separator: "\n\n"))
            }
            // Saved with each train, right after its notification: a run stopped half-way never posts the same notices again.
            store.trainAlertsSeen = TrainAlertWatch.remember(store.trainAlertsSeen, notices: watched, today: today)
        }
    }

    // MARK: Commuter alerts

    /// Saves a commuter alert and plans its next occurrences; the feedback key says what happened.
    func saveCommuterAlert(_ alert: CommuterAlert) async -> String {
        let alerts = [alert] + store.commuterAlerts.filter { $0.id != alert.id }
        store.commuterAlerts = Array(alerts.prefix(CommuterAlert.maximumAlerts))
        commuterAlerts = store.commuterAlerts
        await planCommuterAlerts(now: Date())
        switch CommuterAlertPlanner.nextTrigger(alert, now: Date()) {
        case .at: return "settings.alerts.commuter_scheduled"
        case .disabled: return "settings.alerts.commuter_disabled"
        case .invalidTime: return "settings.alerts.commuter_invalid"
        case .quietHours: return "settings.alerts.commuter_quiet_feedback"
        case .noUpcomingTrigger: return "settings.alerts.commuter_no_trigger"
        }
    }

    /// The commuter alerts of a local backup (Android's import): they replace this iPhone's and are planned again.
    func replaceCommuterAlerts(_ alerts: [CommuterAlert]) async {
        TripNotifier.remove(store.commuterAlerts.flatMap { [TripNotifier.commuterID($0.id, occurrence: 0), TripNotifier.commuterID($0.id, occurrence: 1)] })
        store.commuterAlerts = Array(alerts.prefix(CommuterAlert.maximumAlerts))
        commuterAlerts = store.commuterAlerts
        await planCommuterAlerts(now: Date())
    }

    func removeCommuterAlert(_ id: String) {
        store.commuterAlerts = store.commuterAlerts.filter { $0.id != id }
        commuterAlerts = store.commuterAlerts
        TripNotifier.remove([TripNotifier.commuterID(id, occurrence: 0), TripNotifier.commuterID(id, occurrence: 1)])
    }

    func setCommuterAlertEnabled(_ id: String, enabled: Bool) async {
        store.commuterAlerts = store.commuterAlerts.map { alert in
            var alert = alert
            if alert.id == id { alert.enabled = enabled }
            return alert
        }
        commuterAlerts = store.commuterAlerts
        await planCommuterAlerts(now: Date())
    }

    /// The next two occurrences of every commuter alert as pending notifications (iOS keeps at most 64 in all). Within
    /// 45 minutes of an occurrence the train's status is read and written into it, as Android reads it when it rings.
    private func planCommuterAlerts(now: Date) async {
        var alerts = store.commuterAlerts
        var statuses = store.commuterStatuses
        var changed = false
        for (index, alert) in alerts.enumerated() {
            let ids = [TripNotifier.commuterID(alert.id, occurrence: 0), TripNotifier.commuterID(alert.id, occurrence: 1)]
            // A «once» alert whose occurrence passed is disabled, as when it rings on Android.
            if alert.repeatMode == .once, alert.enabled, let record = statuses[alert.id], record.trigger <= now {
                alerts[index].enabled = false
                changed = true
                TripNotifier.remove(ids)
                continue
            }
            guard case let .at(first) = CommuterAlertPlanner.nextTrigger(alert, now: now) else {
                TripNotifier.remove(ids)
                continue
            }
            var triggers = [first]
            if alert.repeatMode != .once, case let .at(second) = CommuterAlertPlanner.nextTrigger(alert, now: first.addingTimeInterval(1)) {
                triggers.append(second)
            }
            var record = statuses[alert.id]
            if record?.trigger != first { record = TripAlertsStore.CommuterStatusRecord(trigger: first, status: nil) }
            if first.timeIntervalSince(now) <= 45 * 60, record?.status == nil, let status = await commuterStatus(alert, at: first) {
                record?.status = status
            }
            statuses[alert.id] = record
            for (occurrence, trigger) in triggers.enumerated() {
                let status = occurrence == 0 ? record?.status : nil
                await TripNotifier.post(
                    id: ids[occurrence],
                    title: TripTexts.format("notices.alerts.commuter_title", alert.stationName),
                    body: TripTexts.format(status == nil ? "notices.alerts.commuter_body" : "notices.alerts.commuter_body_live",
                                           alert.routeLabel, alert.stationName, alert.time) + " " + TripTexts.status(status),
                    at: trigger, now: now
                )
            }
            if triggers.count < 2 { TripNotifier.remove([ids[1]]) }
        }
        if changed {
            store.commuterAlerts = alerts
            commuterAlerts = alerts
        }
        store.commuterStatuses = statuses.filter { id, _ in alerts.contains { $0.id == id } }
    }

    /// The train scheduled at the alert's time at its station, from the station's live board: only verified values
    /// (Android's `CommuterAlertStatusRules`); nil when no source has the train or the live data is off.
    private func commuterStatus(_ alert: CommuterAlert, at trigger: Date) async -> CommuterLiveStatus? {
        guard let liveData, let model, let station = model.tripStation(named: alert.stationName) else { return nil }
        let scheduled = trigger.addingTimeInterval(TimeInterval(alert.leadMinutes) * 60)
        let request = LiveDeparturesRequest(station: station, from: scheduled.addingTimeInterval(-60))
        guard case let .available(board) = await liveData.departures(request) else { return nil }
        let rows = board.departures.filter { abs($0.scheduled.timeIntervalSince(scheduled)) < 60 }
        guard let row = rows.first(where: { $0.expected != nil || $0.status != nil }) else { return nil }
        if row.status?.value == .cancelled { return CommuterLiveStatus(delayMinutes: 0, platform: nil, cancelled: true) }
        guard row.expected != nil else { return nil }
        return CommuterLiveStatus(delayMinutes: max(row.delayMinutes ?? 0, 0), platform: row.platform?.value, cancelled: false)
    }
}

/// When the background refresh task should run next for the trip alerts: read from the stores alone, so
/// EveningAlerts.schedule can ask it from any context.
enum TripBackgroundSchedule {
    static func nextRunAt(_ now: Date) -> Date? {
        let next = dates(now)
        return [next.followed, next.approach, next.trainAlerts, next.commuter].compactMap { $0 }.min()
    }

    /// The next time each trip check is due («Attività in background» lists them one by one).
    static func dates(_ now: Date) -> (followed: Date?, approach: Date?, trainAlerts: Date?, commuter: Date?) {
        let store = TripAlertsStore()
        var followed: Date?
        if store.followedTrip != nil, let next = store.followedState.nextRefreshAt { followed = max(next, now) }
        let states = store.approachStates
        var approach: [Date] = []
        for alert in store.approachAlerts {
            let state = states[alert.key]
            let next = ApproachAlertPlanner.nextRun(
                alert, doneOn: state?.doneOn.flatMap { PlannedLocalDate(iso: $0) }, notifiedOn: state?.notifiedOn.flatMap { PlannedLocalDate(iso: $0) },
                observation: state?.observation, now: now
            )
            if let next { approach.append(next) }
        }
        var trainAlerts: Date?
        if store.trainAlertsEnabled, !store.trainAlertSubscriptions.isEmpty {
            let legs = store.followedTrip?.legs.map { FollowedTrainTimes(number: $0.number, departure: $0.departure, arrival: $0.arrival) } ?? []
            trainAlerts = TrainAlertWatch.nextRun(store.trainAlertSubscriptions, followed: legs, now: now, lastCheckAt: store.trainAlertsLastCheck)
        }
        var commuter: [Date] = []
        for alert in store.commuterAlerts {
            if case let .at(trigger) = CommuterAlertPlanner.nextTrigger(alert, now: now) { commuter.append(max(trigger.addingTimeInterval(-45 * 60), now)) }
        }
        return (followed, approach.min(), trainAlerts, commuter.min())
    }
}
