import SwiftUI

struct WatchHomeView: View {
    @EnvironmentObject private var store: WatchSnapshotStore
    @Environment(\.scenePhase) private var scenePhase

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
                    Text(store.snapshot.displayFreshnessLabel)
                        .font(.footnote)
                        .foregroundStyle(statusColor)
                        .accessibilityLabel(
                            WatchLocalization.text("watch.accessibility.dataStatus \(store.snapshot.displayFreshnessLabel)")
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
                        ForEach(store.snapshot.upcomingServices.prefix(2)) { upcoming in
                            let service = upcoming.service
                            NavigationLink {
                                WatchServiceDetailView(service: service)
                            } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text("\(service.expectedDeparture)  \(service.destination)")
                                        .font(.body.weight(.semibold))
                                        .foregroundStyle(BinarioPalette.brandGreen)
                                    Text("\(service.category) \(service.number) · \(platformText(service))")
                                        .font(.footnote)
                                    if let departure = upcoming.departure {
                                        Text("watch.countdown.departsIn \(Text(departure, style: .relative))")
                                            .font(.footnote)
                                            .foregroundStyle(.secondary)
                                    }
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
                    Button(WatchLocalization.text("watch.action.refresh"), systemImage: "arrow.clockwise") {
                        Task { await store.requestPhoneRefresh() }
                    }
                    .disabled(store.refreshFeedback == .sending)
                    .accessibilityHint(WatchLocalization.text("watch.hint.refresh"))
                    if let feedback = refreshFeedbackText {
                        Text(feedback)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Binario")
        }
        // Suspended apps resume from memory: drop the trains that left meanwhile.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { store.sceneDidBecomeActive() }
        }
    }

    private var refreshFeedbackText: String? {
        switch store.refreshFeedback {
        case .idle, .sending: return nil
        case .sent: return WatchLocalization.text("watch.refresh.sent")
        case .unreachable: return WatchLocalization.text("watch.refresh.unreachable")
        }
    }

    private var statusColor: Color {
        if store.snapshot.isScheduledOnly { return .secondary }
        switch store.snapshot.freshness {
        case .fresh: return BinarioPalette.brandGreen
        case .aging, .stale: return BinarioPalette.statusWarning
        case .unavailable, .unknown: return BinarioPalette.statusError
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
            LabeledContent(WatchLocalization.text("watch.detail.status"), value: service.displayStatus)
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
