import Foundation
import BinarioKit

/// How a bus of the bundled timetable (`TrainService.vehicle`) is shown, as on Android: a bus icon
/// and its label instead of a train's category («Bus Trenitalia BUS 1234», «ItaloBus 1060», «Bus
/// sostitutivo S11 4417»), in the planner results, the train detail and «Partenze».
enum VehicleTexts {
    /// The SF Symbol of a bus (Android draws `DirectionsBus`).
    static let busSymbol = "bus"

    /// The label of a bus with its published line (`TimetableVehicle.busLabel`), nil for a train.
    /// The keys (`TimetableVehicle.labelKey`, «result.vehicle.*») hold Android's `journey_leg_*`
    /// texts in the 17 languages.
    static func busLabel(_ service: TrainService) -> String? {
        guard let vehicle = service.vehicle, let key = vehicle.labelKey else { return nil }
        let label = NSLocalizedString(key, comment: "")
        return TimetableVehicle.busLabel(label, vehicle: vehicle, line: service.category, number: service.number)
    }

    /// «Bus Trenitalia BUS 1234» for a bus, «REG 2153» for a train (`ReliableAlternatives.trainLabel`).
    static func rowLabel(_ service: TrainService) -> String {
        guard let label = busLabel(service) else { return ReliableAlternatives.trainLabel(service) }
        let number = service.number.trimmingCharacters(in: .whitespaces)
        return number.isEmpty ? label : "\(label) \(number)"
    }
}
