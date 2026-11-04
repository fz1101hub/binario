import Foundation
import SwiftUI
import BinarioKit

/// The texts of the network statistics, bikes and airport links. Counted texts come from
/// Localizable.stringsdict (CLDR plural forms), the others from Localizable.strings; the counted
/// number is always the first argument.
enum ReliabilityTexts {
    // MARK: Results

    /// «In orario 27 volte su 30».
    static func onTime(_ onTime: Int, of total: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.reliability.on_time", comment: ""), onTime, total)
    }

    /// «ritardo medio 3 min».
    static func meanDelay(_ minutes: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.reliability.mean_delay", comment: ""), minutes)
    }

    /// «soppresso 1 volta».
    static func cancelled(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.reliability.cancelled", comment: ""), count)
    }

    /// «Soppresso 12 volte su 12», for a train cancelled on every day observed.
    static func cancelledShare(_ cancelled: Int, of days: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.reliability.cancelled_share", comment: ""), cancelled, days)
    }

    /// The sentence VoiceOver reads, which says the statistics are unofficial.
    static func spoken(_ text: String) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.reliability.spoken", comment: ""), text)
    }

    /// «Statistiche non ufficiali di Binario su dati ViaggiaTreno, aggiornate al 9 nov 2026».
    static func attribution(_ windowEnd: NetworkReliabilityDay) -> String {
        let day = windowEnd.noonInRome()?.formatted(date: .abbreviated, time: .omitted)
            ?? String(format: "%04ld-%02ld-%02ld", locale: Locale(identifier: "en_US_POSIX"), windowEnd.year, windowEnd.month, windowEnd.day)
        return String.localizedStringWithFormat(NSLocalizedString("result.reliability.attribution", comment: ""), day)
    }

    /// The parts of a train's line at the destination: on time and mean delay (and its
    /// cancellations), or only its cancellations when it never ran.
    static func trainParts(_ stop: StopReliability) -> [String] {
        if stop.cancelledEveryDay { return [cancelledShare(stop.daysCancelled, of: stop.daysObserved)] }
        var parts = [onTime(stop.onTime, of: stop.samples), meanDelay(stop.shownMeanDelayMinutes)]
        if stop.daysCancelled > 0 { parts.append(cancelled(stop.daysCancelled)) }
        return parts
    }

    /// «Questa tratta: 83% in orario · ritardo medio 2 min · 12 treni · 109 corse osservate in 30
    /// giorni», then «Partenze più puntuali: 6–9 · meno puntuali: 18–21» when the hours differ.
    static func routeLines(_ route: RoutePunctuality) -> [[String]] {
        var lines = [[
            String.localizedStringWithFormat(NSLocalizedString("result.route.on_time", comment: ""), route.onTimePercent),
            meanDelay(route.shownMeanDelayMinutes),
            String.localizedStringWithFormat(NSLocalizedString("result.route.trains", comment: ""), route.trains),
            String.localizedStringWithFormat(
                NSLocalizedString("result.route.runs_observed", comment: ""),
                route.samples,
                String.localizedStringWithFormat(NSLocalizedString("result.route.days", comment: ""), route.days)
            ),
        ]]
        if let best = route.bestBand, let worst = route.worstBand {
            lines.append([String.localizedStringWithFormat(NSLocalizedString("result.route.hours", comment: ""), band(best), band(worst))])
        }
        return lines
    }

    /// «6–9», the hours of a band.
    static func band(_ band: HourBand) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.route.hour_band", comment: ""), band.startHour, band.endHour)
    }

    // MARK: Detail

    /// «Affidabilità ultimi 30 giorni».
    static func detailTitle(days: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.detail.reliability_title", comment: ""), days)
    }

    /// «Arrivo a Merano: in orario 27 volte su 30».
    static func arrivalOnTime(_ onTime: Int, of total: Int, at station: String) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.detail.arrival_on_time", comment: ""), onTime, total, station)
    }

    /// «Ritardo medio: 3 min».
    static func detailMeanDelay(_ minutes: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.detail.mean_delay", comment: ""), minutes)
    }

    /// «Giorni osservati: 28 · soppressioni: 1».
    static func daysObserved(_ observed: Int, cancelled: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.detail.days", comment: ""), observed, cancelled)
    }

    /// The five bands of the delay distribution with their share and count, in band order.
    static func distributionRows(_ distribution: DelayDistribution) -> [(label: String, value: String)] {
        let labels = [
            NSLocalizedString("result.detail.band_no_delay", comment: ""),
            String.localizedStringWithFormat(NSLocalizedString("result.detail.band_range", comment: ""), 1, 5),
            String.localizedStringWithFormat(NSLocalizedString("result.detail.band_range", comment: ""), 6, 15),
            String.localizedStringWithFormat(NSLocalizedString("result.detail.band_range", comment: ""), 16, 30),
            String.localizedStringWithFormat(NSLocalizedString("result.detail.band_over", comment: ""), 30),
        ]
        let percentages = distribution.percentages
        return distribution.counts.indices.map { index in
            (
                label: labels[index],
                value: String.localizedStringWithFormat(
                    NSLocalizedString("result.detail.band_share", comment: ""),
                    distribution.counts[index],
                    percentages[index]
                )
            )
        }
    }

    /// «Ritardo massimo: 47 min».
    static func maxDelay(_ minutes: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.detail.max_delay", comment: ""), minutes)
    }

    /// «Dove il ritardo cresce di più: Bolzano (in media 4 min in più)».
    static func worstStop(_ station: String, increase minutes: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.detail.worst_stop", comment: ""), station, minutes)
    }

    /// «Bolzano: di solito al binario 3 (8 volte su 10)».
    static func usualPlatform(_ platform: UsualPlatform, at station: String) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("result.detail.usual_platform", comment: ""),
            platform.count,
            platform.observed,
            station,
            platform.name
        )
    }

    /// «Venerdì: in orario 3 volte su 6 (in media 80%)».
    static func weekday(_ remark: WeekdayRemark) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("result.detail.weekday", comment: ""),
            remark.onTime,
            remark.samples,
            weekdayName(remark.weekday),
            remark.overallOnTimePercent
        )
    }

    /// «Conta come in orario un arrivo con al massimo 5 min di ritardo.»
    static func thresholdNote(_ minutes: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.detail.threshold_note", comment: ""), minutes)
    }

    /// The weekday name (ISO 1 = Monday) in the language of the app's strings, capitalised to
    /// open a line.
    static func weekdayName(_ isoWeekday: Int) -> String {
        let locale = Locale(identifier: Bundle.main.preferredLocalizations.first ?? "it")
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        let symbols = calendar.standaloneWeekdaySymbols
        guard symbols.count == 7 else { return String(isoWeekday) }
        // Foundation lists Sunday first.
        let symbol = symbols[isoWeekday % 7]
        return symbol.prefix(1).uppercased(with: locale) + symbol.dropFirst()
    }

    // MARK: Planner

    /// «Cerca i treni da Milano Centrale a Malpensa Aeroporto».
    static func airportSearch(from origin: String, to destination: String) -> String {
        String.localizedStringWithFormat(NSLocalizedString("planner.airports.search", comment: ""), origin, destination)
    }
}

/// «Bici ammessa» or «Bici non ammessa», only for a value the timetable publishes.
struct BikesLabel: View {
    let allowed: Bool

    var body: some View {
        Label(LocalizedStringKey(allowed ? "result.bikes.allowed" : "result.bikes.not_allowed"), systemImage: "bicycle")
    }
}
