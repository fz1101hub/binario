import Foundation
import BinarioKit

/// How a bus or a city line of the bundled timetable (`TrainService.vehicle`) is shown, as on
/// Android: a bus icon and its label instead of a train's category («Bus Trenitalia BUS 1234»,
/// «ItaloBus 1060», «Bus sostitutivo S11 4417»), the mode and line of a metro, tram or funicular
/// («Metro M1», «Tram 14»), in the planner results, the train detail and «Partenze».
enum VehicleTexts {
    /// The SF Symbol of a bus (Android draws `DirectionsBus`).
    static let busSymbol = "bus"

    /// The SF Symbol of a service's vehicle: a bus, or the mode of a city line (Android draws
    /// `Subway`, `Tram` and `DirectionsRailway`); a train's for a train.
    static func symbol(_ service: TrainService) -> String {
        switch service.vehicle {
        case .metro?: return "tram.fill.tunnel"
        case .tram?: return "tram"
        case .funicular?: return "cablecar"
        case nil, .train?: return "train.side.front.car"
        default: return busSymbol
        }
    }

    /// The label of a bus with its published line (`TimetableVehicle.busLabel`), or of a city line
    /// with its line (`TimetableVehicle.urbanLabel`), nil for a train. The keys
    /// (`TimetableVehicle.labelKey`, «result.vehicle.*») hold Android's `journey_leg_*` texts in
    /// the 17 languages.
    static func busLabel(_ service: TrainService) -> String? {
        guard let vehicle = service.vehicle, let key = vehicle.labelKey else { return nil }
        let label = NSLocalizedString(key, comment: "")
        if vehicle.isUrban {
            // A city run has a line but no number: the line is its category (SQLiteGTFSJourneyRepository).
            return TimetableVehicle.urbanLabel(label, line: service.category)
        }
        return TimetableVehicle.busLabel(label, vehicle: vehicle, line: service.category, number: service.number)
    }

    /// «Bus Trenitalia BUS 1234» for a bus, «Metro M1» for a city line, «REG 2153» for a train
    /// (`ReliableAlternatives.trainLabel`).
    static func rowLabel(_ service: TrainService) -> String {
        guard let label = busLabel(service) else { return ReliableAlternatives.trainLabel(service) }
        let number = service.number.trimmingCharacters(in: .whitespaces)
        return number.isEmpty || service.isUrban ? label : "\(label) \(number)"
    }

    /// «dalla fermata Bologna Centrale FS, a pochi passi»: a city line boarded a short walk from the
    /// searched station (Android's `direct_connections_from_stop`); nil when it leaves the station.
    static func boardingStop(_ service: TrainService) -> String? {
        guard let stop = service.boardingStop else { return nil }
        return String.localizedStringWithFormat(NSLocalizedString("result.boarding_stop", comment: ""), stop)
    }
}
