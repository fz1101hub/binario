import SwiftUI

struct WatchHomeView: View {
    @EnvironmentObject private var store: WatchSnapshotStore

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(store.snapshot.displayRouteLabel)
                        .font(.headline)
                        .accessibilityLabel(
                            WatchLocalization.text(
                                "watch.accessibility.route \(store.snapshot.displayRouteLabel) \(store.snapshot.direction.accessibilityLabel)"
                            )
                        )
                    Text(store.snapshot.freshness.label)
                        .font(.footnote)
                        .foregroundStyle(statusColor)
                        .accessibilityLabel(
                            WatchLocalization.text("watch.accessibility.dataStatus \(store.snapshot.freshness.label)")
                        )
                }

                if store.snapshot.services.isEmpty {
                    ContentUnavailableView {
                        Label(
                            WatchLocalization.text("watch.unavailable.title"),
                            systemImage: "train.side.front.car"
                        )
                    } description: {
                        Text(WatchLocalization.text("watch.unavailable.description"))
                    }
                } else {
                    Section(WatchLocalization.text("watch.section.nextServices")) {
                        ForEach(store.snapshot.services.prefix(2)) { service in
                            NavigationLink {
                                WatchServiceDetailView(service: service)
                            } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text("\(service.expectedDeparture)  \(service.destination)")
                                        .font(.body.weight(.semibold))
                                    Text("\(service.category) \(service.number) · \(platformText(service))")
                                        .font(.footnote)
                                }
                            }
                            .accessibilityLabel(service.accessibilitySummary)
                        }
                    }
                }

                Section {
                    Text(WatchLocalization.text("watch.label.source \(store.snapshot.displayProviderName)"))
                    Text(WatchLocalization.text("watch.label.quality \(store.snapshot.confidenceLabel)"))
                    if let notice = store.snapshot.displayNotice {
                        Text(notice)
                            .font(.footnote)
                    }
                    Button(WatchLocalization.text("watch.action.reloadCache"), systemImage: "arrow.clockwise") {
                        store.refreshFromCache()
                    }
                    .accessibilityHint(WatchLocalization.text("watch.hint.reloadCache"))
                }
            }
            .navigationTitle("Binario")
        }
    }

    private var statusColor: Color {
        switch store.snapshot.freshness {
        case .fresh: return .green
        case .aging: return .yellow
        case .stale: return .orange
        case .unavailable, .unknown: return .red
        }
    }

    private func platformText(_ service: WatchService) -> String {
        guard let platform = service.platform else {
            return WatchLocalization.text("watch.platform.none")
        }
        return WatchLocalization.text("watch.platform.value \(platform)")
    }
}

private struct WatchServiceDetailView: View {
    let service: WatchService

    var body: some View {
        List {
            Text(service.destination).font(.headline)
            LabeledContent(
                WatchLocalization.text("watch.detail.train"),
                value: "\(service.category) \(service.number)"
            )
            LabeledContent(WatchLocalization.text("watch.detail.expected"), value: service.expectedDeparture)
            LabeledContent(WatchLocalization.text("watch.detail.scheduled"), value: service.scheduledDeparture)
            LabeledContent(
                WatchLocalization.text("watch.detail.platform"),
                value: service.platform ?? WatchLocalization.text("watch.detail.noValue")
            )
            LabeledContent(WatchLocalization.text("watch.detail.status"), value: service.status)
            if service.delayMinutes > 0 {
                LabeledContent(
                    WatchLocalization.text("watch.detail.delay.label"),
                    value: WatchLocalization.text("watch.detail.delay \(service.delayMinutes)")
                )
            }
        }
        .navigationTitle(WatchLocalization.text("watch.detail.title"))
        .accessibilityElement(children: .contain)
    }
}
