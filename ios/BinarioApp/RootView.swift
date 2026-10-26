import SwiftUI

struct RootView: View {
    private enum Tab: Hashable { case today, planner, saved, settings }
    @State private var selection = Tab.today

    var body: some View {
        TabView(selection: $selection) {
            NavigationStack { CommuterView(isVisible: selection == .today) }
                .tabItem { Label("commuter.tab", systemImage: "house") }
                .tag(Tab.today)

            NavigationStack { PlannerView() }
                .tabItem { Label("tab.planner", systemImage: "point.topleft.down.to.point.bottomright.curvepath") }
                .tag(Tab.planner)

            NavigationStack { SavedRoutesView() }
                .tabItem { Label("tab.saved", systemImage: "star") }
                .tag(Tab.saved)

            NavigationStack { SettingsView() }
                .tabItem { Label("tab.settings", systemImage: "gearshape") }
                .tag(Tab.settings)
        }
    }
}
