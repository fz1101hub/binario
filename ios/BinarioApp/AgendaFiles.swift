import Foundation
import SwiftUI
import UniformTypeIdentifiers
import BinarioKit

/// The Agenda's files, in Android's formats so either phone reads the other's. Binario writes no
/// file of its own: an export is made in memory only when the user shares it (the system's share
/// sheet holds it, and Files saves it where the user chooses), and a picked file is read in place,
/// bounded, through its security-scoped address, never copied into the app.
enum AgendaFiles {
    /// ".ics", or the generic calendar type where the system has no declared one.
    static let calendarType: UTType = UTType(filenameExtension: "ics") ?? .calendarEvent

    /// What «Importa» offers: an Agenda export (JSON) or a calendar Binario wrote.
    static let importTypes: [UTType] = [.json, calendarType, .calendarEvent]

    /// The calendar's words in the user's language; times and Binario's fields stay neutral.
    static func icsText() -> PlannedJourneyIcsText {
        let format = NSLocalizedString("saved.agenda.ics_description", comment: "")
        return PlannedJourneyIcsText(
            describe: { segment in
                String.localizedStringWithFormat(
                    format,
                    segment.origin.displayName, segment.destination.displayName,
                    segment.departure.date.iso, segment.departure.time.hhmm, segment.departure.timeZone
                )
            },
            reminderDescription: NSLocalizedString("saved.agenda.ics_reminder", comment: "")
        )
    }

    /// The picked file, read where it is: at most one byte more than the transfer limit, so a
    /// larger file is refused without being read whole. Nil when it cannot be read or is too large.
    static func read(_ url: URL) -> Data? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped { url.stopAccessingSecurityScopedResource() }
        }
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        guard let data = try? handle.read(upToCount: PlannedJourneySchema.maximumEncodedBytes + 1),
              data.count <= PlannedJourneySchema.maximumEncodedBytes
        else { return nil }
        return data
    }
}

/// The Agenda as Android's transfer document, made when the user shares it.
struct AgendaJSONFile: Transferable {
    let journeys: [PlannedJourney]

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .json) { file in
            try PlannedJourneyTransfer.encode(PlannedJourneyCollection(validating: file.journeys))
        }
        .suggestedFileName("binario-agenda.json")
    }
}

/// Journeys as an ICS calendar in Android's format, made when the user shares it.
struct AgendaCalendarFile: Transferable {
    let journeys: [PlannedJourney]

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: AgendaFiles.calendarType) { file in
            Data(try PlannedJourneyIcs.export(file.journeys, generatedAt: Date(), text: AgendaFiles.icsText()).utf8)
        }
        .suggestedFileName("binario-agenda.ics")
    }
}
