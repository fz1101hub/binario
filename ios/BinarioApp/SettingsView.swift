import SwiftUI

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

            Section("settings.privacy.title") {
                Text("settings.privacy.body")
                Label("settings.privacy.no_collection", systemImage: "hand.raised")
                Label("settings.privacy.local_routes", systemImage: "iphone")
                Label("settings.privacy.watch", systemImage: "applewatch")
                Toggle(
                    "settings.privacy.watch_sharing",
                    isOn: Binding(
                        get: { model.watchSharingEnabled },
                        set: model.setWatchSharingEnabled
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
