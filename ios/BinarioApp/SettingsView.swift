import SwiftUI
import BinarioKit

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var extras: ExtrasModel
    @AppStorage(UrbanStopsSetting.key) private var urbanStopsInSearch = UrbanStopsSetting.defaultValue

    var body: some View {
        List {
            Section("settings.data.title") {
                LabeledContent("settings.data.catalog") {
                    Text(catalogName)
                        .multilineTextAlignment(.trailing)
                }
                LabeledContent("settings.data.provider") {
                    Text(providerStatus)
                        .multilineTextAlignment(.trailing)
                }
                if let expiry = model.firstPackExpiry {
                    LabeledContent("settings.data.valid_until") {
                        Text(expiry.formatted(date: .long, time: .omitted))
                            .multilineTextAlignment(.trailing)
                    }
                }
                Text("settings.data.catalog_notice")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            // «Preferenze di viaggio» of the journeys with changes, as on Android (Impostazioni).
            Section {
                NavigationLink {
                    TravelPreferencesView()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("settings.travel.title")
                        Text("settings.travel.detail")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            // Guideline 5.2.2 and CC BY: every bundled feed is attributed with its licence and
            // source page, not only the feed that happens to answer the current search.
            Section("settings.sources.title") {
                if model.schedulePacks.isEmpty {
                    Text(providerStatus)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.schedulePacks, id: \.sourceID) { pack in
                        SourcePackRow(pack: pack, source: model.sourcePolicy?.source(id: pack.sourceID))
                    }
                }
                // The city stops among the station suggestions, as on Android (`UrbanStopsSetting`).
                if !model.urbanStops.stops.isEmpty {
                    Toggle("settings.urban_stops.title", isOn: $urbanStopsInSearch)
                }
            }

            // The full timetables of the neighbouring countries, downloaded only after a tap
            // (design §4.4); hidden when this build pins none.
            ForeignPacksSettingsSection()

            // Android has no switch for its live sources; the iPhone has one, on by default, and
            // says what each source receives (PRIVACY.md, «Dati in tempo reale»).
            Section("settings.live.sources_title") {
                Toggle(
                    "settings.live.toggle",
                    isOn: Binding(
                        get: { model.liveDataEnabled },
                        set: { enabled in model.setLiveDataEnabled(enabled) }
                    )
                )
                Text("settings.live.detail")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                LiveSourceRow(name: LiveTexts.sourceName(.viaggiaTreno), detailKey: "settings.live.viaggiatreno")
                LiveSourceRow(name: LiveTexts.sourceName(.rfiStationBoard), detailKey: "settings.live.rfi")
                LiveSourceRow(name: LiveTexts.sourceName(.italo), detailKey: "settings.live.italo")
                // Android's personal board of transport.opendata.ch, and its open Trip Updates feeds, each asked only
                // for the trains of its own pack and so listed only when that pack is in the timetable.
                LiveSourceRow(name: LiveTexts.sourceName(.swissOpenTransport), detailKey: "settings.live.swiss")
                ForEach(tripUpdateSources, id: \.self) { source in
                    LiveSourceRow(name: LiveTexts.sourceName(source), detailKey: "settings.live.trip_updates")
                }
                // The city networks' feeds of a personal build whose city pack is in the timetable.
                ForEach(UrbanRealtimeBuild.feeds(packs: Set(model.includedPacks.map(\.sourceID))), id: \.id) { feed in
                    LiveSourceRow(name: feed.displayName, detailKey: "settings.live.trip_updates")
                }
                // The notice sources Android counts among its personal ones, behind the same switch.
                LiveSourceRow(name: NoticePublisher.trenitalia.brand, detailKey: "settings.live.trenitalia_news")
                LiveSourceRow(name: NoticePublisher.trenord.brand, detailKey: "settings.live.trenord")
                // True only while no evening alert reads these feeds in the background; the alerts
                // say themselves when they read them (ExtrasSettingsSection).
                if !extras.anyEveningAlertEnabled {
                    Text("settings.live.notices_always")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Text("settings.live.privacy")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            // «Meteo a destinazione» and the evening alerts, as Android's settings.
            ExtrasSettingsSection()

            Section("settings.privacy.title") {
                Text("settings.privacy.body")
                Label("settings.privacy.no_collection", systemImage: "hand.raised")
                Label("settings.privacy.local_routes", systemImage: "iphone")
                Label("settings.privacy.watch", systemImage: "applewatch")
                Toggle(
                    "settings.privacy.watch_sharing",
                    isOn: Binding(
                        get: { model.watchSharingEnabled },
                        set: { enabled in model.setWatchSharingEnabled(enabled) }
                    )
                )
                Text("settings.privacy.watch_sharing_detail")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                LabeledContent("settings.privacy.watch_status") {
                    Text(LocalizedStringKey(model.displayedWatchSyncStatus.localizationKey))
                        .multilineTextAlignment(.trailing)
                }
            }

            Section("settings.about.title") {
                LabeledContent("settings.about.version") {
                    Text(model.versionDisplay)
                }
            }
        }
        .navigationTitle("settings.title")
    }

    /// The open Trip Updates feeds of the packs in the timetable, in the shared table's order.
    private var tripUpdateSources: [LiveSource] {
        let packs = Set(model.includedPacks.map(\.sourceID))
        return SharedData.RealtimeFeeds.tripUpdates.filter { packs.contains($0.pack) }.compactMap { LiveSource(rawValue: $0.id) }
    }

    // Both rows describe the data actually loaded: the catalogue names itself and the feed
    // count follows the index, so neither drifts when the assets are refreshed.
    private var catalogName: String {
        switch model.catalogState {
        case let .available(catalog): catalog.sourceDatabaseName
        case .loading: String(localized: "settings.data.provider_loading")
        case .unavailable: String(localized: "settings.data.provider_unavailable")
        }
    }

    private var providerStatus: String {
        switch model.scheduleState {
        case .loading: String(localized: "settings.data.provider_loading")
        case .available: String(
            format: String(localized: "settings.data.provider_programmed"),
            locale: .current,
            model.includedPacks.count
        )
        case .unavailable: String(localized: "settings.data.provider_unavailable")
        }
    }
}

/// One live source and what the iPhone sends it; the name is never translated.
private struct LiveSourceRow: View {
    let name: String
    let detailKey: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(verbatim: name)
            Text(detailKey)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct SourcePackRow: View {
    let pack: GTFSSourcePack
    let source: GTFSSourcePolicy.Source?

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(pack.attribution)
            if pack.tripCount > 0 {
                Text("\(String(localized: "settings.sources.valid_until")) \(pack.lastServiceDate.formatted(date: .long, time: .omitted))")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Text("settings.sources.no_trips")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if let licence = source?.licence {
                Text("\(String(localized: "settings.sources.licence")) \(licence)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if let source {
                // The feed's public page (licence and terms), not the archive the index was built from.
                let page = source.landingPage ?? source.sourceURL
                Link(page.host() ?? page.absoluteString, destination: page)
                    .font(.footnote)
            }
        }
    }
}
