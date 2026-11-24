import SwiftUI
import WidgetKit

/// The iPhone's home-screen widgets, Android's three: «Tratta», «Tabellone stazione», «I miei treni».
///
/// Target `BinarioWidgets`, bundle `it.binario.ios.widgets`, embedded in Binario.app/PlugIns. The
/// followed trip's Live Activity is its own extension (`ios/BinarioLiveActivity`), with no data of
/// this one (ios/APPLE_PLATFORM_READINESS.md, «Widget e Live Activity»).
@main
struct BinarioWidgetsBundle: WidgetBundle {
    var body: some Widget {
        RouteWidget()
        StationWidget()
        TrainsWidget()
    }
}
