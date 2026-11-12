import SwiftUI
import BinarioKit

/// «Scrivi o detta la tratta» (EXPO_PORT_PLAN I6), above the station pickers of the planner and of
/// the Agenda editor's first leg. The user types a sentence, or says it with the keyboard's own
/// dictation key: Binario never records audio and asks for no microphone or speech permission. On
/// «Applica» the sentence is read on the iPhone with Android's dictation rules, in the language of
/// the app, against the catalogue, and kept nowhere. A confirmed route fills both stations and the
/// day and time said; stations not found leave them alone and bring only the day and time; an
/// ambiguous or incomplete sentence changes nothing. The line under the field is Android's feedback.
struct RouteAssistSection: View {
    @EnvironmentObject private var model: AppModel
    @Binding var origin: Station?
    @Binding var destination: Station?
    /// Replaced by the day and time said; nil stays nil until one is said.
    @Binding var departure: Date?

    @State private var text = ""
    @State private var feedback: String?
    @State private var reading = false

    var body: some View {
        if RouteDictation.offers(.route, language: AppModel.dictationLanguage) {
            Section {
                TextField("planner.dictation.field", text: $text)
                    .submitLabel(.done)
                    .onSubmit(read)
                Button(action: read) {
                    Label("planner.dictation.apply", systemImage: "text.badge.checkmark")
                }
                .disabled(reading || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                if let feedback {
                    Text(feedback)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } footer: {
                Text(RouteAssistText.footer)
            }
        }
    }

    private func read() {
        let sentence = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !sentence.isEmpty, !reading else { return }
        reading = true
        Task {
            let review = await model.reviewRouteDictation(sentence)
            reading = false
            if let review { apply(review) }
        }
    }

    /// Android's rule (PlannerScreen): the day and time on a confirmed route or on stations not
    /// found, the stations only on a confirmed route.
    private func apply(_ review: DictationReview) {
        let result = review.feedback(.route)
        let input = review.input
        if review.status == .confirmed || result == .dateTimeOnly,
           let instant = input.departure(replacing: departure ?? Date()) {
            departure = instant
        }
        if review.status == .confirmed {
            if let station = input.origin { origin = station }
            if let station = input.destination { destination = station }
        }
        let message = RouteAssistText.feedback(result)
        feedback = message
        DeparturesTexts.announce(message)
    }
}

/// The texts of `RouteAssistSection`, Android's dictation feedback.
enum RouteAssistText {
    /// The hint under the field, with an example sentence of the app's language.
    static var footer: String {
        String.localizedStringWithFormat(
            NSLocalizedString("planner.dictation.footer", comment: ""),
            NSLocalizedString("planner.dictation.example", comment: "")
        )
    }

    static func feedback(_ feedback: DictationFeedback) -> String {
        switch feedback {
        case .confirmed:
            return NSLocalizedString("planner.dictation.confirmed", comment: "")
        case .incomplete:
            return NSLocalizedString("planner.dictation.incomplete", comment: "")
        case .dateTimeOnly:
            return NSLocalizedString("planner.dictation.date_time_only", comment: "")
        case .unresolved:
            return NSLocalizedString("planner.dictation.unresolved", comment: "")
        case let .ambiguous(alternatives):
            let separator = NSLocalizedString("planner.dictation.separator", comment: "")
            let groups = alternatives.map { group -> String in
                let role: String
                switch group.role {
                case .origin, .station: role = NSLocalizedString("planner.dictation.role_origin", comment: "")
                case .destination: role = NSLocalizedString("planner.dictation.role_destination", comment: "")
                }
                return "\(role): \(group.names.joined(separator: separator))"
            }
            return String.localizedStringWithFormat(
                NSLocalizedString("planner.dictation.ambiguous", comment: ""),
                groups.joined(separator: "; ")
            )
        }
    }
}
