import SwiftUI
import BinarioKit

/// «Avvisi per questo treno», Android's `TrainNoticesSection`: the notices ViaggiaTreno publishes for
/// the train on its service day («smart caring», for a Trenitalia train or a ViaggiaTreno run), the
/// Trenitalia news that name it and the Trenord notices of its lines (for a Trenord train or one near
/// Trenord's network). Read only while the detail shows them, with «Dati in tempo reale» on, as the
/// personal sources of Android; hidden when no source applies, so an empty list never reads as «no
/// disruption».
struct TrainNoticesSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var notices: RailNoticesModel
    let service: TrainService
    let query: JourneyQuery
    /// The run the detail shows, for its origin code and stops.
    let run: LiveRunStatus?
    /// The run of the board row the detail was opened from.
    let boardRun: ViaggiaTrenoRunReference?

    @State private var loaded: Loaded?
    @State private var attempt = 0

    struct Loaded: Equatable {
        let train: [LiveTrainNotice]
        let network: [RailNotice]
        let trenord: [RailNotice]
        let unavailable: Bool
        let stale: Bool

        var isEmpty: Bool { train.isEmpty && network.isEmpty && trenord.isEmpty }
    }

    var body: some View {
        if let context, applies(context), let rules = notices.rules {
            Section {
                if let loaded {
                    TrainNoticesContent(loaded: loaded, rules: rules) { attempt += 1 }
                } else {
                    HStack {
                        ProgressView()
                        Text("notices.loading")
                    }
                }
            } header: {
                Text("notices.train.title")
            }
            .task(id: "\(attempt)|\(key(context))") { await load(context) }
        }
    }

    /// What the detail knows of the train (Android's `TrainNoticeContext.from`): the number proper,
    /// the ViaggiaTreno run's day and origin when known, the operator, the stations shown.
    private var context: TrainNoticeContext? {
        let reference = run?.viaggiaTrenoRun ?? boardRun
        let serviceDate = reference.map { Date(timeIntervalSince1970: TimeInterval($0.departureDateMillis) / 1_000) } ?? query.departure
        let origin = model.station(for: query.origin)
        let destination = model.station(for: query.destination)
        let near = notices.rules.map { $0.inTrenordArea(origin) || $0.inTrenordArea(destination) } ?? false
        return TrainNoticeContext(
            number: service.number, serviceDate: serviceDate, originCode: run?.originCode ?? reference?.originCode,
            operatorName: service.operatorName,
            stationNames: [query.origin.name] + (run?.stops.map(\.stationName) ?? []) + [service.destination],
            endpoints: [query.origin.name, query.destination.name], nearTrenordNetwork: near
        )
    }

    /// Android's `appliesTo`: with the personal sources on, the per-train notices of a Trenitalia train
    /// or a ViaggiaTreno run, or a network source of the train.
    private func applies(_ context: TrainNoticeContext) -> Bool {
        guard model.isLiveDataActive, service.hasTrainNumber, let source = notices.service else { return false }
        return context.matchesTrenitaliaNumbers || source.asksNetworkNotices(for: context, personal: model.liveDataEnabled)
    }

    private func key(_ context: TrainNoticeContext) -> String {
        [context.number, String(Int(context.serviceDate.timeIntervalSince1970)), context.originCode ?? "",
         context.operatorID ?? "", context.endpoints.joined(separator: "/"), String(context.nearTrenordNetwork)].joined(separator: "|")
    }

    /// The per-train notices through the live sources, the network ones through the notice sources,
    /// at the same time; a list read for another train or with the switch changed is never shown.
    private func load(_ context: TrainNoticeContext) async {
        let source = notices.service
        let personal = model.liveDataEnabled
        async let network = Self.networkNotices(source, context: context, personal: personal)
        var train: [LiveTrainNotice] = []
        var unavailable = false
        var stale = false
        if context.matchesTrenitaliaNumbers {
            let request = LiveTrainNoticesRequest(number: context.number, serviceDate: context.serviceDate, originCode: context.originCode)
            switch await model.trainNotices(request) {
            case let .available(found)?:
                train = found.notices
                stale = found.stale
            case .unavailable(.notCovered)?, .unavailable(.cancelled)?, nil:
                break
            case .unavailable?:
                unavailable = true
            }
        }
        let others = await network
        if Task.isCancelled || personal != model.liveDataEnabled { return }
        loaded = Loaded(
            train: train, network: others?.network ?? [], trenord: others?.trenord ?? [],
            unavailable: unavailable || others?.unavailable == true, stale: stale || others?.stale == true
        )
    }

    nonisolated private static func networkNotices(
        _ source: RailNoticeService?, context: TrainNoticeContext, personal: Bool
    ) async -> TrainNetworkNotices? {
        guard let source, source.asksNetworkNotices(for: context, personal: personal) else { return nil }
        return await source.trainNetworkNotices(context, personal: personal)
    }
}

/// The notices of a train: each per-train notice with its publication time and the strike badge,
/// ViaggiaTreno named as their source, then the Trenitalia news and the Trenord notices that concern
/// it, and whether a source failed (with «Riprova») or answered from an earlier copy.
private struct TrainNoticesContent: View {
    let loaded: TrainNoticesSection.Loaded
    let rules: NoticeRules
    let retry: () -> Void

    var body: some View {
        if loaded.isEmpty, !loaded.unavailable {
            Text("notices.train.empty")
                .foregroundStyle(.secondary)
        }
        ForEach(loaded.train) { notice in
            VStack(alignment: .leading, spacing: 4) {
                if notice.mentionsStrike {
                    StrikeBadge()
                }
                Text(verbatim: notice.text(for: TrainNoticeTexts.language))
                if let published = notice.publishedAt {
                    Text(verbatim: TrainNoticeTexts.published(published))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
        }
        if !loaded.train.isEmpty {
            Text("notices.train.source")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        if !loaded.network.isEmpty {
            Text("notices.train.network_heading")
                .font(.subheadline.weight(.semibold))
            ForEach(loaded.network) { notice in
                RailNoticeRow(notice: notice, rules: rules)
            }
        }
        if !loaded.trenord.isEmpty {
            Text("notices.train.trenord_heading")
                .font(.subheadline.weight(.semibold))
            ForEach(loaded.trenord) { notice in
                RailNoticeRow(notice: notice, rules: rules)
            }
        }
        if loaded.stale {
            Text("notices.stale")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        if loaded.unavailable {
            Text("notices.unavailable")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Button("notices.retry", action: retry)
        }
    }
}

enum TrainNoticeTexts {
    /// The app's language: the publisher's English text for any language but Italian.
    static var language: String {
        String((Bundle.main.preferredLocalizations.first ?? "it").prefix(2))
    }

    /// «Pubblicato: 13/09/26, 22:14», in the Italian railway time.
    static func published(_ date: Date) -> String {
        let rome = TimeZone(identifier: "Europe/Rome") ?? .current
        let time = date.formatted(Date.FormatStyle(date: .numeric, time: .shortened, timeZone: rome))
        return String.localizedStringWithFormat(NSLocalizedString("notices.train.published_at", comment: ""), time)
    }
}
