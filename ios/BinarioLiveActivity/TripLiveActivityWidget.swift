import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// The extension of the followed trip's Live Activity only; the home-screen widgets live in their own extension.
@main
struct BinarioLiveActivityBundle: WidgetBundle {
    var body: some Widget {
        TripLiveActivityWidget()
    }
}

/// The Lock Screen and Dynamic Island presentations of the followed trip, with «Aggiorna» and «Interrompi».
struct TripLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TripActivityAttributes.self) { context in
            TripLockScreenView(context: context)
                .padding(14)
                .activityBackgroundTint(Color(.systemBackground).opacity(0.85))
                .activitySystemActionForegroundColor(.primary)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: "train.side.front.car")
                        .accessibilityHidden(true)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(context.state.compact)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(context.state.warning ? Color.red : Color.primary)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.state.title)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(context.state.line)
                            .font(.caption)
                            .lineLimit(2)
                        TripActivityButtons(context: context)
                    }
                }
            } compactLeading: {
                Image(systemName: "train.side.front.car")
                    .accessibilityHidden(true)
            } compactTrailing: {
                Text(context.state.compact)
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(context.state.warning ? Color.red : Color.primary)
            } minimal: {
                Image(systemName: context.state.warning ? "exclamationmark.triangle" : "train.side.front.car")
                    .accessibilityLabel(Text(context.state.title))
            }
        }
    }
}

private struct TripLockScreenView: View {
    let context: ActivityViewContext<TripActivityAttributes>

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label {
                Text(context.state.title)
                    .font(.headline)
                    .foregroundStyle(context.state.warning ? Color.red : Color.primary)
            } icon: {
                Image(systemName: context.state.warning ? "exclamationmark.triangle" : "train.side.front.car")
            }
            ForEach(Array(context.state.details.prefix(5).enumerated()), id: \.offset) { _, line in
                Text(line)
                    .font(.subheadline)
                    .lineLimit(2)
            }
            TripActivityButtons(context: context)
        }
        .accessibilityElement(children: .contain)
    }
}

private struct TripActivityButtons: View {
    let context: ActivityViewContext<TripActivityAttributes>

    var body: some View {
        HStack(spacing: 10) {
            Button(intent: TripRefreshIntent(tripID: context.attributes.tripID)) {
                Label(context.attributes.refreshLabel, systemImage: "arrow.clockwise")
            }
            Button(intent: TripStopIntent(tripID: context.attributes.tripID)) {
                Label(context.attributes.stopLabel, systemImage: "xmark")
            }
        }
        .font(.caption.weight(.semibold))
        .buttonStyle(.bordered)
    }
}
