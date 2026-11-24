package it.binario.app.links

import it.binario.app.shared.SharedData
import it.binario.app.data.RailOperator
import java.net.URI
import java.util.Locale

/** What an official page is for, in "Biglietti e assistenza". */
enum class OperatorLinkKind { BUY, CHANGE, SUBSCRIPTIONS, BIKES, CUSTOMER_CARE, REFUNDS, STRIKES, WEBSITE }

data class OperatorLink(val kind: OperatorLinkKind, val url: String)

/** One operator Binario shows timetables of, with its official domains and the pages on them. */
data class OperatorLinks(
    val id: String,
    val name: String,
    /** Registrable domains the operator publishes on; every [links] url must be on one of them. */
    val domains: Set<String>,
    val links: List<OperatorLink>
) {
    fun url(kind: OperatorLinkKind): String? = links.firstOrNull { it.kind == kind }?.url
}

/**
 * Official pages the app only opens in the browser on an explicit tap; the app never requests them itself and sends
 * nothing to them. The operators, their domains and their pages are the shared table
 * contracts/shared-data/v1/official-links-v1.json ([SharedData.OfficialLinks]), read by the iPhone too: only `https`
 * pages on the operators' own domains, preferring home and assistance pages over deep links, checked against a search
 * index in November 2026, not fetched from the build machine. An operator can move them, and then the browser shows the
 * operator's own "page not found".
 *
 * No operator documents a public URL format for prefilling a journey search, so "Apri sul sito ufficiale" opens the
 * purchase home page and never builds a search URL.
 */
object OfficialLinks {
    /** Every operator of "Biglietti e assistenza", the national ones first, with its «treni garantiti» page last. */
    val ALL: List<OperatorLinks> = SharedData.OfficialLinks.OPERATORS.map { operator ->
        OperatorLinks(
            id = operator.id,
            name = operator.name,
            domains = operator.domains,
            links = operator.links.map { OperatorLink(OperatorLinkKind.valueOf(it.kind), it.url) } +
                listOfNotNull(
                    operator.guaranteedTrains?.let { key ->
                        OperatorLink(OperatorLinkKind.STRIKES, SharedData.NoticesSources.LINKS.guaranteedTrains.getValue(key))
                    }
                )
        )
    }

    val TRENITALIA: OperatorLinks = ALL.first { it.id == "trenitalia" }
    val ITALO: OperatorLinks = ALL.first { it.id == "italo" }
    val TRENORD: OperatorLinks = ALL.first { it.id == "trenord" }

    /** Regional railways of the offline timetables: the official site, and a dedicated page where one is known. */
    val REGIONAL: List<OperatorLinks> = SharedData.OfficialLinks.OPERATORS.filterNot { it.national }
        .map { regional -> ALL.first { it.id == regional.id } }

    /** FS News, the news site of the FS Italiane group, with its radio bulletins on rail traffic. Link only. */
    val FS_NEWS = SharedData.NoticesSources.LINKS.fsNews
    val FS_NEWS_DOMAINS = SharedData.OfficialLinks.FS_NEWS_DOMAINS

    /**
     * Sala Blu: RFI's free assistance, on booking, for passengers with a disability or reduced mobility in the stations
     * of the Italian network. [PAGE] is RFI's accessibility page (Sale Blu, accessibility of each station, how to book);
     * the two numbers are those of the national Sala Blu, [TOLL_FREE] free from landlines and [NATIONAL] from any phone
     * at the ordinary rate. Like the operators' pages, they were checked against a search index of rfi.it (its
     * accessibility pages and the Sala Blu press releases of 2025 and 2026) in November 2026, not fetched from the build
     * machine. Opened only on a tap: the page in the browser, a number in the phone's dialer, which Binario never calls.
     */
    object SalaBlu {
        const val PAGE = SharedData.OfficialLinks.SALA_BLU_PAGE
        val DOMAINS = SharedData.OfficialLinks.SALA_BLU_DOMAINS
        const val TOLL_FREE = SharedData.OfficialLinks.SALA_BLU_TOLL_FREE
        const val NATIONAL = SharedData.OfficialLinks.SALA_BLU_NATIONAL

        /** "tel:800906060": the number for the dialer, digits only. */
        fun dialUri(number: String): String = "tel:" + number.filter(Char::isDigit)

        /**
         * True for a station of the Italian network RFI manages: an RFI station code ("S01700") without a foreign
         * Trenitalia location id (Bellinzona is "85…"), and, when the catalogue places it, inside Italy's borders as
         * far as a rough box tells (Modane, Matrei, Stuttgart and Sankt Veit carry RFI codes but are abroad). City stops
         * and stations of other networks have no RFI code and show nothing.
         */
        fun appliesTo(station: it.binario.app.data.Station): Boolean {
            if (station.railwayCode?.let(RFI_STATION_CODE::matches) != true) return false
            if (station.trenitaliaLocationId?.startsWith("83") == false) return false
            val latitude = station.latitude ?: return true
            val longitude = station.longitude ?: return true
            if (latitude !in 35.4..47.05 || longitude !in 6.68..18.6) return false
            // North-east of San Candido the border runs south: Carinthia, not Friuli.
            return !(latitude > 46.6 && longitude > 12.45)
        }

        private val RFI_STATION_CODE = Regex("S\\d{5}")
    }

    /** The operator whose official site sells a journey run by [operator]; null for the others. */
    fun purchaseSiteOf(operator: RailOperator, operatorName: String? = null): OperatorLinks? {
        // The published operator name wins: several sources file every regional train (Trenord, Tper...) under the
        // Trenitalia regional constant, so without a name only Italo and the Trenitalia Frecce and Intercity qualify.
        val name = operatorName?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
        return when {
            name != null -> purchaseSiteOf(listOf(name))
            operator == RailOperator.ITALO -> ITALO
            operator in TRENITALIA_LONG_DISTANCE -> TRENITALIA
            else -> null
        }
    }

    /** The operator of a journey whose legs are all run by one of Trenitalia or Italo; null when mixed or other. */
    fun purchaseSiteOf(operatorNames: List<String?>): OperatorLinks? {
        val sites = operatorNames.map { name ->
            val lower = name.orEmpty().lowercase(Locale.ROOT)
            when {
                lower.contains("italo") -> ITALO
                lower.contains("trenitalia") -> TRENITALIA
                else -> null
            }
        }
        return sites.distinct().singleOrNull()
    }

    /**
     * The operator running a train, for its own pages (bikes on board, refunds and compensation): the published operator
     * name first ("Trenitalia", "TRENORD", "Italo"), else the national operator constant. Trenitalia Tper is a company of
     * its own whose pages Binario does not list, and several sources file every regional train under the Trenitalia
     * regional constant: without a name only Italo, Trenord and the Trenitalia Frecce and Intercity are known.
     */
    fun operatorOf(operator: RailOperator, operatorName: String? = null): OperatorLinks? {
        val name = operatorName?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
        if (name != null) return when {
            name.contains("tper") -> null
            name.contains("italo") || name == "ntv" -> ITALO
            name.contains("trenord") -> TRENORD
            name.contains("trenitalia") -> TRENITALIA
            else -> null
        }
        return when (operator) {
            RailOperator.ITALO -> ITALO
            RailOperator.TRENORD -> TRENORD
            in TRENITALIA_LONG_DISTANCE -> TRENITALIA
            else -> null
        }
    }

    /** "Treni garantiti" page of the publisher of a strike notice (Trenitalia, Trenord); null when none is known. */
    fun guaranteedTrainsOf(publisherId: String): String? = when (publisherId.lowercase(Locale.ROOT)) {
        "trenitalia" -> TRENITALIA.url(OperatorLinkKind.STRIKES)
        "trenord" -> TRENORD.url(OperatorLinkKind.STRIKES)
        else -> null
    }

    /** True for an `https` url whose host is [domains] or one of their subdomains. */
    fun isOfficial(url: String, domains: Set<String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return domains.any { host == it || host.endsWith(".$it") }
    }

    private val TRENITALIA_LONG_DISTANCE = setOf(RailOperator.TRENITALIA_LONG_DISTANCE, RailOperator.TRENITALIA_AV)
}
