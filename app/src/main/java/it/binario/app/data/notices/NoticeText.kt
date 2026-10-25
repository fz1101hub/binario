package it.binario.app.data.notices

import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * Plain text of publisher HTML. The markup is only parsed with jsoup and reduced to text with paragraph breaks;
 * it is never rendered, so scripts, styles and attributes cannot reach the UI.
 */
internal object NoticeText {
    private const val MAX_INPUT = 200_000

    /** HTML whose markup is entity-escaped once more (`&lt;p&gt;Velocit&amp;agrave;…`), as Trenitalia publishes it. */
    fun fromDoubleEscapedHtml(value: String?, maxLength: Int): String? {
        val once = value?.take(MAX_INPUT)?.let { Parser.unescapeEntities(it, false) } ?: return null
        return fromHtml(once, maxLength)
    }

    /** A plain-text field (a title) that may still carry HTML entities: entities decoded, nothing parsed as markup. */
    fun decodeEntities(value: String?, maxLength: Int): String? =
        value?.take(MAX_INPUT)?.let { normalize(Parser.unescapeEntities(it, false), maxLength) }

    fun fromHtml(value: String?, maxLength: Int): String? {
        val html = value?.take(MAX_INPUT)?.takeIf { it.isNotBlank() } ?: return null
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings(Document.OutputSettings().prettyPrint(false))
        document.select("script, style, noscript, template").remove()
        document.select("br").after("\n")
        document.select("p, div, li, h1, h2, h3, h4, tr").before("\n").after("\n")
        document.select("li").prepend("• ")
        return normalize(document.body().wholeText(), maxLength)
    }

    /** Collapses spaces inside lines, keeps at most one empty line between paragraphs and bounds the length. */
    fun normalize(value: String, maxLength: Int): String? {
        val lines = value.replace(' ', ' ').replace("\r", "").split('\n')
            .map { it.replace(Regex("[ \\t\\u000B\\f]+"), " ").trim() }
        val out = StringBuilder()
        var blank = 0
        for (line in lines) {
            if (line.isEmpty()) { blank++; continue }
            if (out.isNotEmpty()) out.append(if (blank > 0) "\n\n" else "\n")
            out.append(line)
            blank = 0
        }
        val text = out.toString().trim()
        if (text.isEmpty()) return null
        return if (text.length <= maxLength) text else text.take(maxLength - 1).trimEnd() + "…"
    }

    private val STRIKE = Regex("\\bscioper[oi]\\b", RegexOption.IGNORE_CASE)

    /** True when the text mentions a strike ("sciopero" / "scioperi"), the trigger for the strike badge. */
    fun mentionsStrike(vararg texts: String?): Boolean = texts.any { it != null && STRIKE.containsMatchIn(it) }

    /** Key used to recognise the same notice published twice (case, spacing and punctuation ignored). */
    fun dedupKey(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}

/** Splits notice text into plain runs and http(s) URLs, so the UI can make only the URLs tappable. */
object NoticeLinks {
    sealed interface Segment {
        val text: String
        data class Plain(override val text: String) : Segment
        data class Link(override val text: String, val url: String) : Segment
    }

    private val URL = Regex("https?://[^\\s<>\"'\\u00A0]+", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?)]}»"
    private const val MIN_URL_LENGTH = 9

    fun segments(text: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var cursor = 0
        for (match in URL.findAll(text)) {
            val url = match.value.trimEnd { it in TRAILING }
            // Shorter than a scheme plus one host character: not a usable link.
            if (url.length < MIN_URL_LENGTH) continue
            if (match.range.first > cursor) out += Segment.Plain(text.substring(cursor, match.range.first))
            out += Segment.Link(url, url)
            cursor = match.range.first + url.length
        }
        if (cursor < text.length) out += Segment.Plain(text.substring(cursor))
        return out
    }
}
