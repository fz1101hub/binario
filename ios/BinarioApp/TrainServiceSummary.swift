import SwiftUI
import BinarioKit

struct TrainServiceSummary: View {
    let service: TrainService
    let availability: SnapshotAvailability
    var prominent = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(availability == .live ? service.expectedDeparture : service.scheduledDeparture)
                .font(prominent ? .largeTitle.bold().monospacedDigit() : .title2.bold().monospacedDigit())
            Text(service.destination).font(.headline)
            Text("\(service.operatorName) · \(service.category) \(service.number)").foregroundStyle(.secondary)
            if availability == .live {
                Text(LocalizedStringKey(statusKey))
                if service.delayMinutes > 0 {
                    Text(String(format: String(localized: "commuter.delay_format"), service.delayMinutes))
                        .fontWeight(.semibold)
                }
                if let platform = service.platform {
                    Text("\(String(localized: "commuter.platform")) \(platform)")
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var statusKey: String {
        switch service.status {
        case "ON_TIME": "commuter.status.on_time"
        case "DELAYED": "commuter.status.delayed"
        case "CANCELLED": "commuter.status.cancelled"
        default: "commuter.unavailable"
        }
    }
}
