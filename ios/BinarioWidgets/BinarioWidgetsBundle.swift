import SwiftUI
import WidgetKit

/// The iPhone's home-screen widgets, Android's three: «Tratta», «Tabellone stazione», «I miei treni».
///
/// This is the app's one WidgetKit extension (target `BinarioWidgets`, bundle `it.binario.ios.widgets`,
/// embedded in Binario.app/PlugIns). A Live Activity joins it rather than adding a second extension:
/// put its `ActivityConfiguration` widget in `ios/BinarioWidgets/` (its attributes in BinarioKit, which
/// the app and this extension both link), list it in `body` below, and add `NSSupportsLiveActivities`
/// to the app's Info.plist (ios/APPLE_PLATFORM_READINESS.md, «Widget e Live Activity»).
@main
struct BinarioWidgetsBundle: WidgetBundle {
    var body: some Widget {
        RouteWidget()
        StationWidget()
        TrainsWidget()
    }
}
