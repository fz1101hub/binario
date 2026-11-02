package it.binario.app.links

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
 * nothing to them. Only `https` pages on the operators' own domains, preferring home and assistance pages over deep
 * links. The pages were checked against a search index in November 2026, not fetched from the build machine: an
 * operator can move them, and then the browser shows the operator's own "page not found".
 *
 * No operator documents a public URL format for prefilling a journey search, so "Apri sul sito ufficiale" opens the
 * purchase home page and never builds a search URL.
 */
object OfficialLinks {
    val TRENITALIA = OperatorLinks(
        id = "trenitalia", name = "Trenitalia", domains = setOf("trenitalia.com"),
        links = listOf(
            OperatorLink(OperatorLinkKind.BUY, "https://www.trenitalia.com/"),
            OperatorLink(OperatorLinkKind.CHANGE, "https://www.trenitalia.com/it/informazioni/la-guida-del-viaggiatore/acquisto-utilizzoemodificabigliettonazionale.html"),
            OperatorLink(OperatorLinkKind.SUBSCRIPTIONS, "https://www.trenitalia.com/it/informazioni/la-guida-del-viaggiatore/gli-abbonamenti-di-trenitalia.html"),
            OperatorLink(OperatorLinkKind.BIKES, "https://www.trenitalia.com/it/servizi/bici-al-seguito.html"),
            OperatorLink(OperatorLinkKind.CUSTOMER_CARE, "https://www.trenitalia.com/it/informazioni.html"),
            OperatorLink(OperatorLinkKind.REFUNDS, "https://www.trenitalia.com/it/informazioni/la-guida-del-viaggiatore/i-rimborsi.html"),
            OperatorLink(OperatorLinkKind.STRIKES, "https://www.trenitalia.com/it/informazioni/treni-garantiti-incasodisciopero.html")
        )
    )

    val ITALO = OperatorLinks(
        id = "italo", name = "Italo", domains = setOf("italotreno.com"),
        links = listOf(
            OperatorLink(OperatorLinkKind.BUY, "https://www.italotreno.com/"),
            OperatorLink(OperatorLinkKind.CHANGE, "https://www.italotreno.com/en/the-train/fares"),
            OperatorLink(OperatorLinkKind.SUBSCRIPTIONS, "https://www.italotreno.com/en/train-offers/season-tickets-carnet/carnet-standard"),
            OperatorLink(OperatorLinkKind.CUSTOMER_CARE, "https://www.italotreno.com/en/support-contacts"),
            OperatorLink(OperatorLinkKind.REFUNDS, "https://www.italotreno.com/en/support-contacts/claims-management")
        )
    )

    val TRENORD = OperatorLinks(
        id = "trenord", name = "Trenord", domains = setOf("trenord.it"),
        links = listOf(
            OperatorLink(OperatorLinkKind.BUY, "https://www.trenord.it/"),
            OperatorLink(OperatorLinkKind.SUBSCRIPTIONS, "https://www.trenord.it/en/tickets/travel-titles/season-tickets/"),
            OperatorLink(OperatorLinkKind.BIKES, "https://www.trenord.it/en/tickets/concessions-and-extras/bike-transportation/"),
            OperatorLink(OperatorLinkKind.CUSTOMER_CARE, "https://www.trenord.it/en/assistance/"),
            OperatorLink(OperatorLinkKind.REFUNDS, "https://www.trenord.it/en/assistance/services/refunds-and-indemnities/request-a-refund/"),
            OperatorLink(OperatorLinkKind.STRIKES, "https://www.trenord.it/en/assistance/useful-information/in-case-of-strike-action/")
        )
    )

    /** Regional railways of the offline timetables: the official site, and a dedicated page where one is known. */
    val REGIONAL: List<OperatorLinks> = listOf(
        OperatorLinks("eav", "EAV", setOf("eavsrl.it"), listOf(OperatorLink(OperatorLinkKind.WEBSITE, "https://www.eavsrl.it/"))),
        OperatorLinks("sad", "SAD (Alto Adige)", setOf("sad.it"), listOf(OperatorLink(OperatorLinkKind.WEBSITE, "https://www.sad.it/it/mezzi/i-treni"))),
        OperatorLinks(
            "trentino-trasporti", "Trentino Trasporti", setOf("trentinotrasporti.it"),
            listOf(
                OperatorLink(OperatorLinkKind.WEBSITE, "https://www.trentinotrasporti.it/"),
                OperatorLink(OperatorLinkKind.SUBSCRIPTIONS, "https://www.trentinotrasporti.it/it/tariffe/abbonamenti")
            )
        ),
        OperatorLinks("tft", "TFT (Toscana)", setOf("trasportoferroviariotoscano.it"), listOf(OperatorLink(OperatorLinkKind.WEBSITE, "https://www.trasportoferroviariotoscano.it/"))),
        OperatorLinks("arst", "ARST (Sardegna)", setOf("arst.sardegna.it"), listOf(OperatorLink(OperatorLinkKind.WEBSITE, "https://www.arst.sardegna.it/servizi-orari/servizi-ferroviari-tpl/"))),
        OperatorLinks("fal", "Ferrovie Appulo Lucane", setOf("ferrovieappulolucane.it"), listOf(OperatorLink(OperatorLinkKind.WEBSITE, "https://ferrovieappulolucane.it/"))),
        OperatorLinks(
            "ferrotramviaria", "Ferrotramviaria (Bari Nord)", setOf("ferrovienordbarese.it"),
            listOf(
                OperatorLink(OperatorLinkKind.WEBSITE, "https://www.ferrovienordbarese.it/"),
                OperatorLink(OperatorLinkKind.BUY, "https://eticket.ferrovienordbarese.it/b2c/web/index.jsp")
            )
        ),
        OperatorLinks("fdg", "Ferrovie del Gargano", setOf("ferroviedelgargano.com"), listOf(OperatorLink(OperatorLinkKind.WEBSITE, "https://ferroviedelgargano.com/"))),
        OperatorLinks("fuc", "Ferrovie Udine Cividale (Mi.Co.Tra.)", setOf("ferrovieudinecividale.it"), listOf(OperatorLink(OperatorLinkKind.WEBSITE, "https://www.ferrovieudinecividale.it/"))),
        OperatorLinks("cotral", "Cotral", setOf("cotralspa.it"), listOf(OperatorLink(OperatorLinkKind.WEBSITE, "https://cotralspa.it/")))
    )

    /** Every operator of "Biglietti e assistenza", the national ones first. */
    val ALL: List<OperatorLinks> = listOf(TRENITALIA, ITALO, TRENORD) + REGIONAL

    /** FS News, the news site of the FS Italiane group, with its radio bulletins on rail traffic. Link only. */
    const val FS_NEWS = "https://www.fsnews.it/"
    val FS_NEWS_DOMAINS = setOf("fsnews.it")

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
