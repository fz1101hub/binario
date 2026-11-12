import SwiftUI
import BinarioKit

/// «Importa da testo» (the companion's guided import): lines pasted or typed, then a preview of
/// the journeys found on catalogue stations, each to keep or leave out, and «Aggiungi» once the
/// user says they checked them. Nothing is added before, nothing is replaced, and no more than the
/// Agenda's free places; the lines left out are counted.
struct AgendaTextImportView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    /// The journeys a calendar of another app proposes (Android's foreign calendar import): the
    /// review starts from them, with no text to type.
    let calendarPreview: PlannedJourneyTextPreview?
    @State private var text = ""
    @State private var preview: PlannedJourneyTextPreview?
    @State private var selected: Set<String> = []
    @State private var reviewed = false
    @State private var messageKey: String?

    init(calendarPreview: PlannedJourneyTextPreview? = nil) {
        self.calendarPreview = calendarPreview
    }

    var body: some View {
        NavigationStack {
            Form {
                if calendarPreview != nil {
                    Section {
                        Text("saved.agenda.calendar_import_hint")
                    }
                } else {
                Section {
                    TextField("saved.agenda.text_import.field", text: $text, axis: .vertical)
                        .lineLimit(4...12)
                        .autocorrectionDisabled()
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("saved.agenda.text_import.hint")
                        Text("saved.agenda.text_import.example")
                            .font(.footnote.monospaced())
                    }
                }

                Section {
                    Button("saved.agenda.text_import.preview", action: makePreview)
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                }

                if let preview {
                    Section {
                        if preview.journeys.isEmpty {
                            Text("saved.agenda.text_import.none")
                        } else {
                            Text(verbatim: AgendaTextImportView.counted("saved.agenda.text_import.found", preview.journeys.count))
                            ForEach(preview.journeys) { journey in
                                Toggle(isOn: Binding(
                                    get: { selected.contains(journey.id) },
                                    set: { isOn in
                                        if isOn { selected.insert(journey.id) } else { selected.remove(journey.id) }
                                    }
                                )) {
                                    AgendaJourneyRow(journey: journey)
                                }
                            }
                        }
                        if preview.skippedLines > 0 {
                            Text(verbatim: AgendaTextImportView.counted("saved.agenda.text_import.skipped", preview.skippedLines))
                                .foregroundStyle(.secondary)
                        }
                    }

                    if !preview.journeys.isEmpty {
                        Section {
                            Toggle("saved.agenda.text_import.reviewed", isOn: $reviewed)
                            Button("saved.agenda.text_import.confirm", action: add)
                                .disabled(!reviewed || selected.isEmpty)
                        }
                    }
                }

                if let messageKey {
                    Section {
                        Text(LocalizedStringKey(messageKey))
                            .foregroundStyle(BinarioPalette.statusError)
                    }
                }
            }
            .navigationTitle(LocalizedStringKey(calendarPreview == nil ? "saved.agenda.text_import" : "saved.agenda.import"))
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                guard let calendarPreview, preview == nil else { return }
                preview = calendarPreview
                selected = Set(calendarPreview.journeys.map(\.id))
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("saved.agenda.cancel") { dismiss() }
                }
            }
            // A new text needs a new preview and a new check.
            .onChange(of: text) { _, _ in
                preview = nil
                reviewed = false
            }
        }
    }

    private func makePreview() {
        messageKey = nil
        switch model.catalogState {
        case .loading:
            show("catalog.loading")
        case .unavailable:
            show("catalog.unavailable.body")
        case .available:
            guard let result = model.previewTextImport(text) else {
                show("saved.agenda.text_import.invalid")
                return
            }
            preview = result
            selected = Set(result.journeys.map(\.id))
            reviewed = false
            let summary = result.journeys.isEmpty
                ? NSLocalizedString("saved.agenda.text_import.none", comment: "")
                : Self.counted("saved.agenda.text_import.found", result.journeys.count)
            AgendaAnnouncement.post(text: summary, .information)
        }
    }

    private func add() {
        guard let preview else { return }
        switch model.addJourneys(preview.journeys.filter { selected.contains($0.id) }) {
        case let .success(added):
            AgendaAnnouncement.post(text: Self.counted("saved.agenda.text_import.added", added), .information)
            dismiss()
        case let .failure(refusal):
            show(AgendaText.messageKey(refusal))
        }
    }

    /// Under the preview, also said by VoiceOver at once.
    private func show(_ key: String) {
        messageKey = key
        AgendaAnnouncement.post(key, .error)
    }

    static func counted(_ key: String, _ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), count)
    }
}
