import SwiftUI
import BinarioKit

struct CommuterView: View {
    var isVisible = true
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var now = Date()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("commuter.title").font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
                    Text("commuter.intro").foregroundStyle(.secondary)
                }

                if let route = model.commuterRoute {
                    routeCard(route)
                    resultCard
                } else {
                    VStack(alignment: .leading, spacing: 16) {
                        Image(systemName: "train.side.front.car").font(.largeTitle).foregroundStyle(.tint)
                        Text("commuter.empty.title").font(.title2.bold())
                        Text("commuter.empty.body").foregroundStyle(.secondary)
                        NavigationLink { PlannerView() } label: {
                            Label("commuter.search", systemImage: "magnifyingglass").frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .commuterCard()
                }

                if !model.savedRoutes.isEmpty {
                    Text("commuter.routes").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    ForEach(model.savedRoutes) { route in
                        Button {
                            Task { await model.followRoute(route) }
                        } label: {
                            HStack(alignment: .top, spacing: 14) {
                                Image(systemName: "star").foregroundStyle(.tint)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(route.name).font(.headline)
                                    Text("\(route.origin.name) → \(route.destination.name)")
                                        .font(.subheadline).foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 0)
                                Image(systemName: "chevron.forward").accessibilityHidden(true)
                            }
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                            .commuterCard()
                        }
                        .buttonStyle(.plain)
                        .disabled(model.isRefreshing || model.scheduleState != .available)
                    }
                }
                Text("commuter.refresh.note").font(.footnote).foregroundStyle(.secondary)
            }
            .padding(20)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("app.name")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await model.refreshCommute(force: true) }
        .task(id: scenePhase == .active && isVisible) {
            guard scenePhase == .active, isVisible else { return }
            while !Task.isCancelled {
                now = Date()
                await model.refreshCommute(now: now)
                do { try await Task.sleep(for: .seconds(15)) } catch { return }
            }
        }
    }

    private func routeCard(_ route: SavedRoute) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("commuter.active", systemImage: "train.side.front.car").font(.subheadline.bold()).foregroundStyle(.tint)
            Text(model.commuterDirection == .return ? route.destination.name : route.origin.name).font(.title2.bold())
            Image(systemName: "arrow.down").foregroundStyle(.secondary).accessibilityHidden(true)
            Text(model.commuterDirection == .return ? route.origin.name : route.destination.name).font(.title2.bold())
            Button {
                Task { await model.followRoute(route, direction: model.commuterDirection == .outbound ? .return : .outbound) }
            } label: {
                Label("commuter.reverse", systemImage: "arrow.up.arrow.down").frame(minHeight: 44)
            }
            .disabled(model.isRefreshing)
            Button {
                Task { await model.refreshCommute(force: true) }
            } label: {
                Label("commuter.refresh", systemImage: "arrow.clockwise").frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.isRefreshing || model.scheduleState != .available)
        }
        .commuterCard()
    }

    @ViewBuilder
    private var resultCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("commuter.next").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            if model.isRefreshing { ProgressView("catalog.loading") }
            if let snapshot = model.latestSnapshot, snapshotMatchesCommute(snapshot) {
                let availability = snapshot.availability(at: now)
                Label(LocalizedStringKey(statusKey(availability)), systemImage: availability == .live ? "dot.radiowaves.left.and.right" : "info.circle")
                    .font(.subheadline.bold())
                Text("\(String(localized: "result.source_label")) \(snapshot.provenance.sourceLabel)")
                    .font(.footnote).foregroundStyle(.secondary)
                Text(snapshot.provenance.observedAt, format: .dateTime.day().month().hour().minute())
                    .font(.footnote).foregroundStyle(.secondary)
                ForEach(Array(snapshot.services.prefix(2))) { service in
                    Divider()
                    TrainServiceSummary(service: service, availability: availability, prominent: true)
                }
            } else if model.scheduleState == .unavailable {
                Label("schedule.unavailable", systemImage: "exclamationmark.triangle")
            } else if case .unavailable(.scheduleExpired) = model.searchState {
                Text("result.expired.body")
            } else if !model.isRefreshing {
                Text("commuter.unavailable").foregroundStyle(.secondary)
            }
        }
        .commuterCard()
    }

    private func snapshotMatchesCommute(_ snapshot: JourneySnapshot) -> Bool {
        guard let route = model.commuterRoute else { return false }
        let reversed = model.commuterDirection == .return
        return snapshot.query.origin.id == (reversed ? route.destination.id : route.origin.id)
            && snapshot.query.destination.id == (reversed ? route.origin.id : route.destination.id)
            && snapshot.query.direction == model.commuterDirection
            && AppModel.isCurrent(snapshot.query, now: now)
    }

    private func statusKey(_ availability: SnapshotAvailability) -> String {
        switch availability {
        case .live: "commuter.data.live"
        case .scheduled: "commuter.data.scheduled"
        case .stale: "commuter.data.stale"
        case .unavailable: "commuter.unavailable"
        }
    }
}

private extension View {
    func commuterCard() -> some View {
        self.padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24))
    }
}
