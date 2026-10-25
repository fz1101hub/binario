import SwiftUI

struct WatchHomeView: View {
    @EnvironmentObject private var store: WatchSnapshotStore

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(store.snapshot.routeLabel)
                        .font(.headline)
                        .accessibilityLabel("Tratta \(store.snapshot.routeLabel), \(store.snapshot.direction.accessibilityLabel)")
                    Text(store.snapshot.freshness.label)
                        .font(.footnote)
                        .foregroundStyle(statusColor)
                        .accessibilityLabel("Stato dato: \(store.snapshot.freshness.label)")
                }

                if store.snapshot.services.isEmpty {
                    ContentUnavailableView(
                        "Dati non disponibili",
                        systemImage: "train.side.front.car",
                        description: Text("dati non disponibili in tempo reale")
                    )
                } else {
                    Section("Prossime corse") {
                        ForEach(store.snapshot.services.prefix(2)) { service in
                            NavigationLink {
                                WatchServiceDetailView(service: service)
                            } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text("\(service.expectedDeparture)  \(service.destination)")
                                        .font(.body.weight(.semibold))
                                        .foregroundStyle(Color(red: 0.56, green: 0.85, blue: 0.58))
                                    Text("\(service.category) \(service.number) · \(platformText(service))")
                                        .font(.footnote)
                                }
                            }
                            .accessibilityLabel(service.accessibilitySummary)
                        }
                    }
                }

                Section {
                    Text("Fonte: \(store.snapshot.providerName)")
                    Text("Qualità: \(store.snapshot.confidenceLabel)")
                    if let notice = store.snapshot.notice {
                        Text(notice)
                            .font(.footnote)
                    }
                    Button("Rileggi cache", systemImage: "arrow.clockwise") {
                        store.refreshFromCache()
                    }
                    .accessibilityHint("Non richiede dati live e ricarica solo l’ultimo snapshot disponibile.")
                }
            }
            .navigationTitle("Binario")
            .tint(Color(red: 0.56, green: 0.85, blue: 0.58))
        }
    }

    private var statusColor: Color {
        switch store.snapshot.freshness {
        case .fresh: return .green
        case .aging: return .yellow
        case .stale: return .orange
        case .unavailable: return .red
        }
    }

    private func platformText(_ service: WatchService) -> String {
        service.platform.map { "Bin. \($0)" } ?? "Bin. —"
    }
}

private struct WatchServiceDetailView: View {
    let service: WatchService

    var body: some View {
        List {
            Text(service.destination).font(.headline)
            LabeledContent("Treno", value: "\(service.category) \(service.number)")
            LabeledContent("Previsto", value: service.expectedDeparture)
            LabeledContent("Programma", value: service.scheduledDeparture)
            LabeledContent("Binario", value: service.platform ?? "Non disponibile")
            LabeledContent("Stato", value: service.status)
            if service.delayMinutes > 0 {
                LabeledContent("Ritardo", value: "\(service.delayMinutes) minuti")
            }
        }
        .navigationTitle("Corsa")
        .accessibilityElement(children: .contain)
    }
}
