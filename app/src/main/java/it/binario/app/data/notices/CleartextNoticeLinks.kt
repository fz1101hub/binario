package it.binario.app.data.notices

import java.net.URI
import java.util.Locale

/**
 * Links in notices that reach the app over the single cleartext hop (ViaggiaTreno redirects its HTTPS JSON to
 * `http://www.viaggiatreno.it`): anyone on the network path could rewrite them. Only HTTPS links to the publisher's own
 * domains stay tappable; any other URL in the text loses its scheme, so the notice still shows it as plain text. The
 * same rule is applied again where the link is made ([NoticeLinks.segments] with [allowed], through
 * [RailNotice.allowsLink]), so no later transformation of the text can bring a link back (audit2 security-privacy-1).
 */
internal object CleartextNoticeLinks {
    /** Domains of the FS group publishers of these notices (Trenitalia, ViaggiaTreno, RFI, FS Italiane), with subdomains. */
    private val OFFICIAL_DOMAINS = listOf("trenitalia.com", "trenitalia.it", "viaggiatreno.it", "fsitaliane.it", "rfi.it", "fsnews.it")

    fun allowed(url: String): Boolean = try {
        val uri = URI(url.trim())
        val host = uri.host?.lowercase(Locale.ROOT)
        uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && host != null && uri.port in setOf(-1, 443) &&
            OFFICIAL_DOMAINS.any { host == it || host.endsWith(".$it") }
    } catch (_: Exception) {
        false
    }

    /** The notice's own link, kept only when [allowed]. */
    fun link(url: String?): String? = url?.trim()?.takeIf(::allowed)

    /**
     * [text] with every URL that is not [allowed] reduced to plain text (scheme removed). Removing a scheme can
     * uncover another one ("https://https://host", "https://a/https://host"): repeated until nothing changes. Every
     * changing pass makes the text shorter, so it ends.
     */
    fun neutralize(text: String?): String? = text?.let {
        var current = it
        while (true) {
            val next = withoutForeignSchemes(current)
            if (next == current) break
            current = next
        }
        current
    }

    private fun withoutForeignSchemes(text: String): String = NoticeLinks.segments(text).joinToString("") { segment ->
        when (segment) {
            is NoticeLinks.Segment.Plain -> segment.text
            is NoticeLinks.Segment.Link -> if (allowed(segment.url)) segment.text else segment.text.substringAfter("://")
        }
    }
}
