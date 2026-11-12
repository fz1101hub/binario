import SwiftUI
import BinarioKit
#if canImport(UIKit)
import UIKit
#endif

/// «Agenda» (in «Tratte»): the journeys planned on this iPhone only, «Prossimi» (the first to
/// leave first) or «Archiviati» (completed or departed, the latest first), on the Italian clock.
/// Dates and times are the stored Italian ones, never converted to the phone's zone. A journey
/// opens its page (checklist, «Modifica», «Completa», «Elimina»); «+» plans a new one.
struct AgendaView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var filter: AgendaFilter = .upcoming
    @State private var editor: AgendaEditorRequest?
    /// The minute the lists are read at: a journey that departs moves to «Archiviati».
    @State private var clock = Date()
    @State private var importing = false
    /// A picked file, read and compared with the Agenda; nothing is written before a choice.
    @State private var filePreview: PlannedJourneyFilePreview?
    /// What the last export or import did, shown at the top of the list.
    @State private var fileMessage: (key: String, isError: Bool)?

    enum AgendaFilter: Hashable {
        case upcoming
        case archive
    }

    private var journeys: [PlannedJourney] {
        switch filter {
        case .upcoming: PlannedJourneyAgenda.upcoming(model.agenda.journeys, now: clock)
        case .archive: PlannedJourneyAgenda.archive(model.agenda.journeys, now: clock)
        }
    }

    var body: some View {
        List {
            if model.agenda.isReadOnly {
                Section {
                    Label("saved.agenda.unreadable", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(BinarioPalette.statusError)
                }
            }

            if let fileMessage {
                Section {
                    Label(LocalizedStringKey(fileMessage.key), systemImage: fileMessage.isError ? "exclamationmark.triangle" : "checkmark.circle")
                        .foregroundStyle(fileMessage.isError ? BinarioPalette.statusError : Color.primary)
                }
            }

            Section {
                Picker("saved.agenda.title", selection: $filter) {
                    Text("saved.agenda.upcoming").tag(AgendaFilter.upcoming)
                    Text("saved.agenda.archive").tag(AgendaFilter.archive)
                }
                .pickerStyle(.segmented)
            }

            Section {
                if journeys.isEmpty {
                    Text("saved.agenda.empty")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(journeys) { journey in
                        NavigationLink {
                            AgendaJourneyView(journeyID: journey.id)
                        } label: {
                            AgendaJourneyRow(journey: journey)
                        }
                    }
                }
            } footer: {
                Text("saved.agenda.local_only")
            }
        }
        .navigationTitle("saved.agenda.title")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    editor = .new
                } label: {
                    Label("saved.agenda.add", systemImage: "plus")
                }
                .disabled(model.agenda.isReadOnly)
            }
            // Android's formats: the JSON document restores editable journeys, the calendar is a
            // copy for other apps. Readable journeys can be exported also from a read-only Agenda.
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    ShareLink(item: AgendaJSONFile(journeys: model.agenda.journeys), preview: SharePreview("saved.agenda.export_json")) {
                        Label("saved.agenda.export_json", systemImage: "square.and.arrow.up")
                    }
                    .disabled(model.agenda.journeys.isEmpty)
                    ShareLink(item: AgendaCalendarFile(journeys: model.agenda.journeys), preview: SharePreview("saved.agenda.export_ics")) {
                        Label("saved.agenda.export_ics", systemImage: "calendar")
                    }
                    .disabled(model.agenda.journeys.isEmpty)
                    Button {
                        fileMessage = nil
                        importing = true
                    } label: {
                        Label("saved.agenda.import", systemImage: "square.and.arrow.down")
                    }
                    .disabled(model.agenda.isReadOnly)
                } label: {
                    Label("saved.agenda.files", systemImage: "arrow.up.arrow.down.circle")
                }
            }
        }
        .sheet(item: $editor) { request in
            AgendaEditorView(request: request)
        }
        // Cancelling the picker is not an error: nothing is called then.
        .fileImporter(isPresented: $importing, allowedContentTypes: AgendaFiles.importTypes) { result in
            guard case let .success(url) = result, let data = AgendaFiles.read(url),
                  let preview = model.previewAgendaFile(data)
            else {
                report("saved.agenda.import_invalid", isError: true)
                return
            }
            filePreview = preview
        }
        .alert(
            Text(LocalizedStringKey(AgendaText.filePreviewTitleKey(filePreview))),
            isPresented: Binding(
                get: { filePreview != nil },
                set: { if !$0 { filePreview = nil } }
            ),
            presenting: filePreview
        ) { preview in
            if case let .journeys(journeys) = preview, journeys.hasChanges {
                if journeys.conflicts.isEmpty {
                    Button("saved.agenda.import") { apply(journeys, .keepExisting) }
                } else {
                    Button("saved.agenda.import_keep") { apply(journeys, .keepExisting) }
                    Button("saved.agenda.import_replace", role: .destructive) { apply(journeys, .replaceExisting) }
                }
                Button("saved.agenda.cancel", role: .cancel) {}
            } else {
                Button("action.ok", role: .cancel) {}
            }
        } message: { preview in
            Text(verbatim: AgendaText.filePreviewMessage(preview))
        }
        .onAppear {
            model.reloadAgenda()
            clock = Date()
            Task { await model.refreshReminders() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                model.reloadAgenda()
                clock = Date()
            }
        }
        .task { await followTheClock() }
    }

    private func apply(_ preview: PlannedJourneyImportPreview, _ policy: PlannedJourneyConflictPolicy) {
        if let refusal = model.importJourneys(preview, policy: policy) {
            report(AgendaText.importMessageKey(refusal), isError: true)
        } else {
            report("saved.agenda.import_done", isError: false)
        }
    }

    /// Shown at the top of the list and said by VoiceOver.
    private func report(_ key: String, isError: Bool) {
        fileMessage = (key, isError)
        AgendaAnnouncement.post(key, isError ? .error : .information)
    }

    /// Every minute while the Agenda is open; nothing is asked anywhere.
    private func followTheClock() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 60 * 1_000_000_000)
            clock = Date()
        }
    }
}

/// One journey of the list: the route, the stored Italian date and time, the label.
struct AgendaJourneyRow: View {
    let journey: PlannedJourney

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: AgendaText.route(journey))
                .font(.headline)
                .foregroundStyle(.primary)
            Text(verbatim: AgendaText.departure(journey))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if !journey.label.isEmpty {
                Text(verbatim: journey.label)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The page of one journey: its values, its checklist to tick and its actions.
struct AgendaJourneyView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let journeyID: String
    @State private var editor: AgendaEditorRequest?
    @State private var confirmingDelete = false
    @State private var messageKey: String?

    private var journey: PlannedJourney? {
        model.agenda.journeys.first { $0.id == journeyID }
    }

    var body: some View {
        Group {
            if let journey {
                content(journey)
            } else {
                ContentUnavailableView("saved.agenda.empty", systemImage: "calendar")
            }
        }
        .sheet(item: $editor) { request in
            AgendaEditorView(request: request)
        }
        .alert(
            LocalizedStringKey(messageKey ?? "saved.agenda.storage_error"),
            isPresented: Binding(
                get: { messageKey != nil },
                set: { if !$0 { messageKey = nil } }
            )
        ) {
            Button("action.ok", role: .cancel) { messageKey = nil }
        }
    }

    private func content(_ journey: PlannedJourney) -> some View {
        let readOnly = model.agenda.isReadOnly
        return List {
            Section {
                LabeledContent("planner.origin", value: journey.origin.displayName)
                LabeledContent("planner.destination", value: journey.destination.displayName)
                LabeledContent("planner.departure", value: AgendaText.departure(journey))
                if !journey.label.isEmpty {
                    LabeledContent("saved.agenda.label", value: journey.label)
                }
                LabeledContent("saved.agenda.reminder", value: AgendaText.reminderChoice(journey.reminderMinutes))
            } footer: {
                if AppModel.needsTimetableZoneCaption(at: journey.departure.instant) {
                    Text("planner.departure.italian_time")
                }
            }

            // What the system really holds, not only the lead chosen.
            let reminder = model.reminderStatus.state(of: journey, now: Date())
            if let key = AgendaText.reminderStateKey(reminder) {
                Section {
                    Label(LocalizedStringKey(key), systemImage: AgendaText.reminderSymbol(reminder))
                    if case .notAllowed = reminder, let url = AgendaText.notificationSettingsURL {
                        Button("saved.agenda.reminder.settings") { openURL(url) }
                    }
                }
            }

            if !journey.checklist.isEmpty {
                Section("saved.agenda.checklist") {
                    ForEach(journey.checklist) { item in
                        Toggle(isOn: Binding(
                            get: { item.completed },
                            set: { report(model.setChecklistItem(item.id, completed: $0, journeyID: journey.id)) }
                        )) {
                            Text(verbatim: item.label)
                        }
                    }
                }
                .disabled(readOnly)
            }

            // One event in Android's calendar format, through the system's share sheet.
            Section {
                ShareLink(item: AgendaCalendarFile(journeys: [journey]), preview: SharePreview("saved.agenda.export_calendar")) {
                    Label("saved.agenda.export_calendar", systemImage: "calendar.badge.plus")
                }
            }

            // «Ritorno» and «Ripianifica» open the editor on a new journey: date and time are chosen anew.
            Section {
                Button {
                    editor = .returnTrip(journey)
                } label: {
                    Label("saved.agenda.return", systemImage: "arrow.uturn.backward")
                }
                Button {
                    editor = .replan(journey)
                } label: {
                    Label("saved.agenda.replan", systemImage: "arrow.clockwise")
                }
            }
            .disabled(readOnly)

            Section {
                if journey.status == .active {
                    Button {
                        editor = .edit(journey)
                    } label: {
                        Label("saved.agenda.edit", systemImage: "pencil")
                    }
                    Button {
                        if let refusal = model.completeJourney(id: journey.id) {
                            report(refusal)
                        } else {
                            AgendaAnnouncement.post("saved.agenda.completed", .information)
                        }
                    } label: {
                        Label("saved.agenda.complete", systemImage: "checkmark.circle")
                    }
                }
                Button(role: .destructive) {
                    confirmingDelete = true
                } label: {
                    Label("saved.agenda.delete", systemImage: "trash")
                }
                .confirmationDialog("saved.agenda.delete_confirmation", isPresented: $confirmingDelete, titleVisibility: .visible) {
                    Button("saved.agenda.delete", role: .destructive) {
                        if let refusal = model.deleteJourney(id: journey.id) {
                            report(refusal)
                        } else {
                            AgendaAnnouncement.post("saved.agenda.deleted", .information)
                            dismiss()
                        }
                    }
                }
            }
            .disabled(readOnly)
        }
        .navigationTitle(Text(verbatim: AgendaText.route(journey)))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func report(_ refusal: PlannedJourneyRefusal?) {
        guard let refusal else { return }
        messageKey = AgendaText.messageKey(refusal)
    }
}

/// What opens the editor.
enum AgendaEditorRequest: Identifiable {
    case new
    case edit(PlannedJourney)
    /// «Ritorno»: the stations swapped, on a date and time chosen anew.
    case returnTrip(PlannedJourney)
    /// «Ripianifica»: the same stations, on a date and time chosen anew.
    case replan(PlannedJourney)

    var id: String {
        switch self {
        case .new: "new"
        case let .edit(journey): "edit:\(journey.id)"
        case let .returnTrip(journey): "return:\(journey.id)"
        case let .replan(journey): "replan:\(journey.id)"
        }
    }

    /// What the editor says first, for a journey prepared from another.
    var noticeKey: String? {
        switch self {
        case .new, .edit: nil
        case .returnTrip: "saved.agenda.return_prepared"
        case .replan: "saved.agenda.replan_prepared"
        }
    }
}

/// The editor of a journey, in a sheet: the planner's station pickers, the date and time in
/// Italian time, an optional label and the checklist typed one line per item.
struct AgendaEditorView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let request: AgendaEditorRequest

    @State private var label = ""
    @State private var origin: Station?
    @State private var destination: Station?
    /// Nil until chosen.
    @State private var departure: Date?
    @State private var checklistText = ""
    @State private var reminderMinutes: Int?
    @State private var messageKey: String?
    @State private var loaded = false
    /// The journey is saved and its reminder is being scheduled: «Salva» waits.
    @State private var saving = false
    /// Saved, but its reminder could not be scheduled: said before the editor closes.
    @State private var savedNoticeKey: String?
    /// The past-departure warning was shown: a second «Salva» saves (Android's rule).
    @State private var pastDepartureConfirmed = false

    private var existing: PlannedJourney? {
        if case let .edit(journey) = request { return journey }
        return nil
    }

    var body: some View {
        NavigationStack {
            Form {
                if let noticeKey = request.noticeKey {
                    Section {
                        Label(LocalizedStringKey(noticeKey), systemImage: "info.circle")
                    }
                }

                Section {
                    StationPickerField(titleKey: "planner.origin", selection: $origin)
                    StationPickerField(titleKey: "planner.destination", selection: $destination)
                    if let departure {
                        // Stored and shown in Italian time: pick it in the same zone.
                        DatePicker("planner.departure", selection: Binding(
                            get: { departure },
                            set: { self.departure = $0 }
                        ))
                        .environment(\.timeZone, ItalianWallClock.timeZone)
                        if AppModel.needsTimetableZoneCaption(at: departure) {
                            Text("planner.departure.italian_time")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        // A return or a replanned journey: the date and time are chosen anew.
                        Button {
                            departure = PlannedJourneyForm.new(now: Date()).departureInstant
                        } label: {
                            LabeledContent("planner.departure") {
                                Text("saved.agenda.unset")
                            }
                        }
                    }
                }

                Section {
                    TextField("saved.agenda.label", text: $label)
                }

                Section {
                    TextField("saved.agenda.checklist", text: $checklistText, axis: .vertical)
                        .lineLimit(3...8)
                } header: {
                    Text("saved.agenda.checklist")
                } footer: {
                    Text("saved.agenda.checklist_hint")
                }

                // A local notification at the Italian departure minus the lead; the permission is
                // asked only when a journey with a reminder is saved.
                Section {
                    Picker("saved.agenda.reminder", selection: $reminderMinutes) {
                        ForEach(PlannedJourneyForm.reminderChoices, id: \.self) { choice in
                            Text(verbatim: AgendaText.reminderChoice(choice)).tag(choice)
                        }
                    }
                } footer: {
                    Text("saved.agenda.reminder.hint")
                }

                // «Preparazione del viaggio»: ready-made lines added after the user's own.
                Section("saved.agenda.preparation") {
                    ForEach(PlannedJourneyChecklistPreset.allCases, id: \.self) { preset in
                        Button {
                            checklistText = preset.applying(to: checklistText) { AgendaText.checklistLine($0) }
                        } label: {
                            Label(LocalizedStringKey(AgendaText.presetKey(preset)), systemImage: AgendaText.presetSymbol(preset))
                        }
                        .accessibilityHint(Text("saved.agenda.preparation_hint"))
                    }
                }

                if let messageKey {
                    Section {
                        Text(LocalizedStringKey(messageKey))
                            .foregroundStyle(BinarioPalette.statusError)
                    }
                }
            }
            .navigationTitle(LocalizedStringKey(existing == nil ? "saved.agenda.add" : "saved.agenda.edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("saved.agenda.cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("saved.agenda.save", action: save)
                        .disabled(saving)
                }
            }
            .onAppear(perform: load)
            .onChange(of: departure) { _, _ in pastDepartureConfirmed = false }
            .alert(
                LocalizedStringKey(savedNoticeKey ?? "saved.agenda.saved"),
                isPresented: Binding(
                    get: { savedNoticeKey != nil },
                    set: { if !$0 { savedNoticeKey = nil } }
                )
            ) {
                if savedNoticeKey == "saved.agenda.saved_reminder_denied", let url = AgendaText.notificationSettingsURL {
                    Button("saved.agenda.reminder.settings") {
                        savedNoticeKey = nil
                        openURL(url)
                        dismiss()
                    }
                }
                Button("action.ok", role: .cancel) {
                    savedNoticeKey = nil
                    dismiss()
                }
            }
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        let form: PlannedJourneyForm
        switch request {
        case .new: form = PlannedJourneyForm.new(now: Date())
        case let .edit(journey): form = PlannedJourneyForm(editing: journey)
        case let .returnTrip(journey): form = PlannedJourneyForm.returnTrip(from: journey)
        case let .replan(journey): form = PlannedJourneyForm.replan(from: journey)
        }
        label = form.label
        origin = form.origin.map { Station(id: $0.id, name: $0.name, railwayCode: $0.railwayCode) }
        destination = form.destination.map { Station(id: $0.id, name: $0.name, railwayCode: $0.railwayCode) }
        departure = form.departureInstant
        checklistText = form.checklistText
        reminderMinutes = form.reminderMinutes
    }

    private func save() {
        var form = PlannedJourneyForm(
            label: label,
            origin: origin.map { StationIdentity($0) },
            destination: destination.map { StationIdentity($0) },
            checklistText: checklistText,
            reminderMinutes: reminderMinutes
        )
        if let departure {
            form.date = ItalianWallClock.date(of: departure)
            form.time = ItalianWallClock.time(of: departure)
        }
        let now = Date()
        let journey: PlannedJourney
        switch form.journey(id: UUID().uuidString, now: now, editing: existing) {
        case let .failure(issue):
            show(AgendaText.messageKey(issue))
            return
        case let .success(built):
            journey = built
        }
        if form.needsPastDepartureWarning(now: now, editing: existing), !pastDepartureConfirmed {
            // Said once; the next «Salva» saves the journey, which goes straight to the archive.
            show("saved.agenda.departure_past")
            pastDepartureConfirmed = true
            return
        }
        if let refusal = model.saveJourney(journey, isNew: existing == nil) {
            show(AgendaText.messageKey(refusal))
            return
        }
        saving = true
        Task {
            let outcome = await model.scheduleReminder(afterSaving: journey)
            saving = false
            switch outcome {
            case .none, .scheduled:
                AgendaAnnouncement.post(AgendaText.savedMessageKey(outcome), .information)
                dismiss()
            case .notAllowed, .tooLate, .failed:
                // The journey is saved: the alert says why its reminder is not, then closes the editor.
                savedNoticeKey = AgendaText.savedMessageKey(outcome)
            }
        }
    }

    /// A message under the form, also said by VoiceOver at once.
    private func show(_ key: String) {
        messageKey = key
        AgendaAnnouncement.post(key, .error)
    }
}

/// VoiceOver announcements of the Agenda.
enum AgendaAnnouncement {
    /// Says the text of `key` when VoiceOver runs: an error at once, information after what is
    /// being said.
    @MainActor
    static func post(_ key: String, _ severity: PlannedJourneyFeedbackSeverity) {
        #if canImport(UIKit)
        let text = NSAttributedString(
            string: NSLocalizedString(key, comment: ""),
            attributes: [.accessibilitySpeechQueueAnnouncement: severity.queuesBehindCurrentSpeech]
        )
        UIAccessibility.post(notification: .announcement, argument: text)
        #endif
    }
}

/// The Agenda's texts that depend on values.
enum AgendaText {
    /// «Roma Termini → Milano Centrale».
    static func route(_ journey: PlannedJourney) -> String {
        "\(journey.origin.displayName) → \(journey.destination.displayName)"
    }

    /// «gio 12 nov 2026 · 08:15»: the stored Italian date in the user's language and the stored
    /// Italian time as it was saved, never a conversion to the phone's zone.
    static func departure(_ journey: PlannedJourney) -> String {
        "\(date(journey.departure.date)) · \(journey.departure.time.hhmm)"
    }

    static func date(_ date: PlannedLocalDate) -> String {
        // Midday of that Italian day, written in the Italian zone, is that day in every calendar.
        guard let midday = PlannedLocalTime(hour: 12, minute: 0),
              let instant = ItalianWallClock.instant(date: date, time: midday)
        else { return date.iso }
        var style = Date.FormatStyle.dateTime.weekday(.abbreviated).day().month(.abbreviated).year()
        style.timeZone = ItalianWallClock.timeZone
        return instant.formatted(style)
    }

    /// «Nessuno» or «15 min prima», the minutes written by the system in the user's language.
    static func reminderChoice(_ minutes: Int?) -> String {
        guard let minutes else { return NSLocalizedString("saved.agenda.reminder.none", comment: "") }
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = [.minute]
        formatter.unitsStyle = .short
        let duration = formatter.string(from: TimeInterval(minutes * 60)) ?? "\(minutes)"
        return String.localizedStringWithFormat(NSLocalizedString("saved.agenda.reminder.before", comment: ""), duration)
    }

    /// The line under a journey with a reminder; nil for «Nessuno».
    static func reminderStateKey(_ state: PlannedJourneyReminderState) -> String? {
        switch state {
        case .none: nil
        case .scheduled: "saved.agenda.reminder.scheduled"
        case .notAllowed: "saved.agenda.reminder.not_allowed"
        case .tooLate: "saved.agenda.reminder.too_late"
        case .notScheduled: "saved.agenda.reminder.not_scheduled"
        }
    }

    static func reminderSymbol(_ state: PlannedJourneyReminderState) -> String {
        if case .scheduled = state { return "bell" }
        return "bell.slash"
    }

    /// What is said once a journey is saved, by what became of its reminder.
    static func savedMessageKey(_ outcome: PlannedJourneyReminderOutcome) -> String {
        switch outcome {
        case .none: "saved.agenda.saved"
        case .scheduled: "saved.agenda.saved_with_reminder"
        case .notAllowed: "saved.agenda.saved_reminder_denied"
        case .tooLate: "saved.agenda.saved_reminder_too_late"
        case .failed: "saved.agenda.saved_reminder_failed"
        }
    }

    /// The app's page in the iPhone's settings, where notifications are allowed again.
    @MainActor
    static var notificationSettingsURL: URL? {
        #if canImport(UIKit)
        URL(string: UIApplication.openNotificationSettingsURLString)
        #else
        nil
        #endif
    }

    static func filePreviewTitleKey(_ preview: PlannedJourneyFilePreview?) -> String {
        if case .calendar = preview { return "saved.agenda.ics_preview_title" }
        return "saved.agenda.import_preview_title"
    }

    /// The counts of a picked file, in Android's words.
    static func filePreviewMessage(_ preview: PlannedJourneyFilePreview) -> String {
        switch preview {
        case let .journeys(journeys):
            String.localizedStringWithFormat(
                NSLocalizedString("saved.agenda.import_preview_body", comment: ""),
                journeys.accepted.count, journeys.duplicates.count, journeys.conflicts.count, journeys.unreadableRecords
            )
        case let .calendar(calendar):
            String.localizedStringWithFormat(
                NSLocalizedString("saved.agenda.ics_preview_body", comment: ""),
                calendar.matching.count, calendar.conflicting.count, calendar.unlinked.count
            )
        }
    }

    static func importMessageKey(_ refusal: PlannedJourneyRefusal) -> String {
        switch refusal {
        case .full: "saved.agenda.import_full"
        case .readOnly: "saved.agenda.read_only"
        case .duplicate, .notFound, .invalid, .storageFailure: "saved.agenda.storage_error"
        }
    }

    static func presetKey(_ preset: PlannedJourneyChecklistPreset) -> String {
        switch preset {
        case .commuter: "saved.agenda.preset.commuter"
        case .connection: "saved.agenda.preset.connection"
        case .bike: "saved.agenda.preset.bike"
        case .assistance: "saved.agenda.preset.assistance"
        }
    }

    static func presetSymbol(_ preset: PlannedJourneyChecklistPreset) -> String {
        switch preset {
        case .commuter: "briefcase"
        case .connection: "arrow.triangle.branch"
        case .bike: "bicycle"
        case .assistance: "figure.roll"
        }
    }

    /// A preset line in the user's language, stored as typed.
    static func checklistLine(_ line: PlannedJourneyChecklistLine) -> String {
        let key: String
        switch line {
        case .timeMargin: key = "saved.agenda.check.time_margin"
        case .documents: key = "saved.agenda.check.documents"
        case .belongings: key = "saved.agenda.check.belongings"
        case .connection: key = "saved.agenda.check.connection"
        case .bike: key = "saved.agenda.check.bike"
        case .assistance: key = "saved.agenda.check.assistance"
        }
        return NSLocalizedString(key, comment: "")
    }

    static func messageKey(_ refusal: PlannedJourneyRefusal) -> String {
        switch refusal {
        case .readOnly: "saved.agenda.read_only"
        case .full: "saved.agenda.full"
        case .duplicate: "saved.agenda.duplicate"
        case .notFound, .storageFailure: "saved.agenda.storage_error"
        case let .invalid(error): error == .sameStation ? "planner.same_station" : "saved.agenda.invalid"
        }
    }

    static func messageKey(_ issue: PlannedJourneyFormIssue) -> String {
        switch issue {
        case .missingStations: "planner.selection_required"
        case .sameStation: "planner.same_station"
        case .invalidDeparture, .invalid: "saved.agenda.invalid"
        case .labelTooLong: "saved.agenda.label_too_long"
        case .checklistLineTooLong: "saved.agenda.checklist_too_long"
        case .sensitiveChecklist: "saved.agenda.checklist_sensitive"
        }
    }
}
