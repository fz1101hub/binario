import SwiftUI
import BinarioKit

/// A listed train: its programmed departure, bikes on board when the timetable says, and the
/// «Affidabilità ultimi N giorni» block of the unofficial network statistics at the searched
/// destination. Every line is a sentence of its own; nothing is said by colour alone.
struct TrainDetailView: View {
    @EnvironmentObject private var model: AppModel
    let service: TrainService
    let query: JourneyQuery

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(service.scheduledDeparture)
                            .font(.title3.monospacedDigit().weight(.semibold))
                        Text(service.number)
                            .font(.title3.weight(.semibold))
                        Spacer()
                        Text(service.category)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(verbatim: "\(query.origin.name) → \(service.destination)")
                    Text(service.operatorName)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
                if let bikesAllowed = service.bikesAllowed {
                    BikesLabel(allowed: bikesAllowed)
                }
                Label("result.programmed.disclosure", systemImage: "clock")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            if let detail = model.reliabilityDetail(for: service, query: query) {
                NetworkReliabilitySection(detail: detail, query: query, service: service)
            }
        }
        .navigationTitle(Text(verbatim: service.number))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct NetworkReliabilitySection: View {
    @EnvironmentObject private var model: AppModel
    let detail: NetworkReliabilityDetail
    let query: JourneyQuery
    let service: TrainService

    var body: some View {
        let arrival = detail.arrival
        Section {
            if arrival.cancelledEveryDay {
                // Cancelled on every day observed: no delays to measure.
                Text(ReliabilityTexts.cancelledShare(detail.daysCancelled, of: detail.daysObserved))
                    .font(.body.weight(.semibold))
            } else {
                Text(ReliabilityTexts.arrivalOnTime(arrival.onTime, of: arrival.samples, at: arrivalName))
                    .font(.body.weight(.semibold))
                Text(ReliabilityTexts.detailMeanDelay(arrival.shownMeanDelayMinutes))
                Text(ReliabilityTexts.daysObserved(detail.daysObserved, cancelled: detail.daysCancelled))
                if let distribution = detail.distribution {
                    DelayDistributionRows(distribution: distribution)
                }
                if let maxDelay = detail.maxDelayMinutes {
                    Text(ReliabilityTexts.maxDelay(maxDelay))
                }
            }
            if let worst = detail.worstStop, let name = model.stationName(forReliabilityCode: worst.stationCode) {
                Text(ReliabilityTexts.worstStop(name, increase: worst.increaseMinutes))
            }
            if let platform = detail.usualPlatform {
                Text(ReliabilityTexts.usualPlatform(platform, at: stationName(platform.stationCode, fallback: query.origin.name)))
            }
            ForEach(detail.weekdayRemarks, id: \.weekday) { remark in
                Text(ReliabilityTexts.weekday(remark))
            }
            if !arrival.cancelledEveryDay {
                Text(ReliabilityTexts.thresholdNote(arrival.onTimeLimitMinutes))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Text(ReliabilityTexts.attribution(detail.windowEnd))
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Label(ReliabilityTexts.detailTitle(days: detail.windowDays), systemImage: "chart.bar")
        }
    }

    /// The stop measured, as the search names it: the destination, else the catalogue name of its
    /// code (a train matched at its terminus), else the train's destination.
    private var arrivalName: String {
        if detail.arrival.stationCode == NetworkReliability.stationCode(query.destination) {
            return query.destination.name
        }
        return model.stationName(forReliabilityCode: detail.arrival.stationCode) ?? service.destination
    }

    private func stationName(_ code: String, fallback: String) -> String {
        if code == NetworkReliability.stationCode(query.origin) { return query.origin.name }
        return model.stationName(forReliabilityCode: code) ?? fallback
    }
}

/// «Distribuzione dei ritardi»: one row per band with its share and count, read as one sentence.
private struct DelayDistributionRows: View {
    let distribution: DelayDistribution

    var body: some View {
        let rows = ReliabilityTexts.distributionRows(distribution)
        VStack(alignment: .leading, spacing: 4) {
            Text("result.detail.distribution_title")
                .font(.subheadline.weight(.semibold))
            ForEach(rows.indices, id: \.self) { index in
                LabeledContent(rows[index].label, value: rows[index].value)
                    .font(.subheadline)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
