import Foundation

/// What an official page is for, in «Biglietti e assistenza» (Android's `OperatorLinkKind`).
public enum OperatorLinkKind: String, CaseIterable, Sendable {
    case buy = "BUY"
    case change = "CHANGE"
    case subscriptions = "SUBSCRIPTIONS"
    case bikes = "BIKES"
    case customerCare = "CUSTOMER_CARE"
    case refunds = "REFUNDS"
    case strikes = "STRIKES"
    case website = "WEBSITE"
}

public struct OperatorLink: Equatable, Sendable {
    public let kind: OperatorLinkKind
    public let url: URL
}

/// One operator Binario shows timetables of, with its official domains and its pages.
public struct OperatorLinks: Equatable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let domains: Set<String>
    public let links: [OperatorLink]

    public func url(_ kind: OperatorLinkKind) -> URL? {
        links.first { $0.kind == kind }?.url
    }
}

/// The official pages the app opens in the browser only on a tap, never requesting them: the shared
/// table contracts/shared-data/v1/official-links-v1.json (`SharedData.OfficialLinks`), as Android's
/// `OfficialLinks`. The «treni garantiti» page of an operator comes from the notice sources' links.
public enum OfficialLinks {
    /// Every operator of «Biglietti e assistenza», the national ones first.
    public static let all: [OperatorLinks] = SharedData.OfficialLinks.operators.map { entry in
        var links = entry.links.compactMap { link -> OperatorLink? in
            guard let kind = OperatorLinkKind(rawValue: link.kind), let url = URL(string: link.url) else { return nil }
            return OperatorLink(kind: kind, url: url)
        }
        if let key = entry.guaranteedTrains,
           let page = SharedData.NoticesSources.links.guaranteedTrains[key].flatMap({ URL(string: $0) }) {
            links.append(OperatorLink(kind: .strikes, url: page))
        }
        return OperatorLinks(id: entry.id, name: entry.name, domains: entry.domains, links: links)
    }

    public static func operatorLinks(id: String) -> OperatorLinks? {
        all.first { $0.id == id }
    }

    /// The operator whose site sells a journey run by `operatorName` (Trenitalia or Italo only,
    /// Android's `purchaseSiteOf`); nil for the others.
    public static func purchaseSite(operatorName: String) -> OperatorLinks? {
        let lower = operatorName.lowercased()
        if lower.contains("italo") { return operatorLinks(id: "italo") }
        if lower.contains("trenitalia") { return operatorLinks(id: "trenitalia") }
        return nil
    }

    /// The operator running a train, for its own pages (bikes on board, refunds), Android's
    /// `operatorOf` with a published name: Trenitalia Tper has pages of its own Binario does not list.
    public static func operatorOf(operatorName: String) -> OperatorLinks? {
        let lower = operatorName.trimmingCharacters(in: .whitespaces).lowercased()
        if lower.isEmpty || lower.contains("tper") { return nil }
        if lower.contains("italo") || lower == "ntv" { return operatorLinks(id: "italo") }
        if lower.contains("trenord") { return operatorLinks(id: "trenord") }
        if lower.contains("trenitalia") { return operatorLinks(id: "trenitalia") }
        return nil
    }

    /// The project's public issue form with `title` filled in («Suggerisci una stazione»).
    public static func newIssue(title: String) -> URL? {
        var components = URLComponents(string: SharedData.OfficialLinks.projectNewIssueUrl)
        components?.queryItems = [URLQueryItem(name: "title", value: title)]
        return components?.url
    }

    /// Sala Blu, RFI's free assistance on booking for passengers with reduced mobility.
    public enum SalaBlu {
        public static var page: URL? { URL(string: SharedData.OfficialLinks.salaBluPage) }
        public static var tollFree: String { SharedData.OfficialLinks.salaBluTollFree }
        public static var national: String { SharedData.OfficialLinks.salaBluNational }

        /// «tel:800906060»: the number for the dialer, digits only.
        public static func dialURL(_ number: String) -> URL? {
            URL(string: "tel:" + number.filter { $0.isASCII && $0.isNumber })
        }

        /// A station of the Italian network RFI manages, as Android's `SalaBlu.appliesTo`: an RFI
        /// code («S01700») without a foreign location id, inside a rough box of Italy.
        public static func applies(to station: Station) -> Bool {
            guard let code = station.railwayCode, code.count == 6, code.hasPrefix("S"),
                  code.dropFirst().allSatisfy({ $0.isASCII && $0.isNumber }) else { return false }
            if let location = station.trenitaliaLocationId, !location.hasPrefix("83") { return false }
            guard let latitude = station.latitude, let longitude = station.longitude else { return true }
            guard (35.4...47.05).contains(latitude), (6.68...18.6).contains(longitude) else { return false }
            // North-east of San Candido the border runs south: Carinthia, not Friuli.
            return !(latitude > 46.6 && longitude > 12.45)
        }
    }
}
