import SwiftUI
import BinarioKit

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        List {
            Section("settings.data.title") {
                LabeledContent("settings.data.catalog") {
                    Text("stazioni-ferroviarie-curato-v4")
                }
                LabeledContent("settings.data.provider") {
                    Text(providerStatusKey)
                        .multilineTextAlignment(.trailing)
                }
                Text("settings.data.catalog_notice")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            // Guideline 5.2.2 and CC BY: every bundled feed is attributed with its licence and
            // source page, not only the feed that happens to answer the current search.
            Section("settings.sources.title") {
                if model.schedulePacks.isEmpty {
                    Text(providerStatusKey)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.schedulePacks, id: \.sourceID) { pack in
                        SourcePackRow(pack: pack, source: model.sourcePolicy?.source(id: pack.sourceID))
                    }
                }
            }

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
                    Text(LocalizedStringKey(model.watchSyncStatus.localizationKey))
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

    private var providerStatusKey: LocalizedStringKey {
        switch model.scheduleState {
        case .loading: "settings.data.provider_loading"
        case .available: "settings.data.provider_programmed"
        case .unavailable: "settings.data.provider_unavailable"
        }
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
                Link(source.sourceURL.host() ?? source.sourceURL.absoluteString, destination: source.sourceURL)
                    .font(.footnote)
            }
        }
    }
}
