package it.binario.app

import java.text.Normalizer
import java.util.Locale

/** Dictated text as [PlannerVoiceParser] compares it, in any of the scripts of the app. */
internal object VoiceText {
    private val notAWord = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val spaces = Regex("\\s+")
    private val spaceBetweenNativeLetters = Regex("(?<=[\\p{L}\\p{M}&&[^\\p{IsLatin}]]) (?=[\\p{L}&&[^\\p{IsLatin}]])")
    private const val TATWEEL = 0x0640
    private val latinLetterFolds = mapOf(
        'ı'.code to "i", 'đ'.code to "d", 'ð'.code to "d", 'ø'.code to "o", 'ł'.code to "l", 'ß'.code to "ss",
        'æ'.code to "ae", 'œ'.code to "oe", 'þ'.code to "th", 'ħ'.code to "h"
    )

    /**
     * Lower case; digits of every script in ASCII («٢٠:١٥», «२०:१५», «၂၀:၁၅», «๒๐:๑๕», «２０：１５» are 20:15);
     * compatibility forms folded (full-width letters, «：»); punctuation as a space; a space between two scripts
     * («9時35分» is «9 時 35 分», «Pietro까지» is «pietro 까지»). Latin letters lose their accents and tone marks
     * («Mañana», «yarın», «Từ», «đến»), unless [foldLatinMarks] is false: Vietnamese words differ by them. The marks
     * of the other scripts are part of their letters and stay, except the optional vowel signs of Arabic («غدًا»
     * is «غدا», «إلى» is «الى»).
     */
    fun normalize(value: String, foldLatinMarks: Boolean = true): String {
        val decomposed = Normalizer.normalize(raw(value), Normalizer.Form.NFD)
        val folded = StringBuilder(decomposed.length)
        var script = Character.UnicodeScript.COMMON
        var index = 0
        while (index < decomposed.length) {
            val codePoint = decomposed.codePointAt(index)
            index += Character.charCount(codePoint)
            if (isMark(codePoint)) {
                val optional = (script == Character.UnicodeScript.LATIN && foldLatinMarks) ||
                    (script == Character.UnicodeScript.ARABIC && (codePoint in 0x064B..0x065F || codePoint == 0x0670))
                if (!optional) folded.appendCodePoint(codePoint)
                continue
            }
            if (codePoint == TATWEEL) continue
            script = Character.UnicodeScript.of(codePoint)
            val replacement = if (foldLatinMarks) latinLetterFolds[codePoint] else null
            if (replacement != null) folded.append(replacement) else folded.appendCodePoint(codePoint)
        }
        val words = Normalizer.normalize(folded, Normalizer.Form.NFC).replace(notAWord, " ")
        return spacedScripts(words).replace(spaces, " ").trim()
    }

    /** Chinese, Japanese, Thai and Burmese do not separate their words: a space inside their text means nothing. */
    fun joinNativeWords(text: String): String = text.replace(spaceBetweenNativeLetters, "")

    /** Digits of any script in ASCII and full-width forms folded, in lower case; punctuation kept («9:35», «12/11»). */
    fun raw(value: String): String = asciiDigits(Normalizer.normalize(value, Normalizer.Form.NFKC)).lowercase(Locale.ROOT)

    /** «٩٥١٢», «९५१२» and «９５１２» are 9512: recognizers write digits in the script of their language. */
    fun asciiDigits(value: String): String = buildString(value.length) {
        value.forEach { char -> append(if (char.isDigit() && char !in '0'..'9') Character.forDigit(Character.digit(char, 10), 10) else char) }
    }

    private fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    private fun spacedScripts(text: String): String {
        val spaced = StringBuilder(text.length + 8)
        var previous: Any? = null
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            val kind: Any? = when {
                codePoint == ' '.code -> null
                Character.isDigit(codePoint) -> "digit"
                isMark(codePoint) -> previous
                else -> when (val script = Character.UnicodeScript.of(codePoint)) {
                    Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> previous
                    Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Character.UnicodeScript.HAN
                    else -> script
                }
            }
            if (kind != null && previous != null && kind != previous) spaced.append(' ')
            spaced.appendCodePoint(codePoint)
            previous = kind
        }
        return spaced.toString()
    }
}

/** A day and a month; [monthFirst] when the month comes first («October 9», «11月12日»); group 3 is the year. */
internal class DatePattern(val pattern: String, val monthFirst: Boolean = false)

internal enum class StationRole { FROM, TO }

/**
 * The words of one language for a day, a time, a return and the role of a station. Words are written as people
 * write them and compared after [text]; the [times] and [dates] patterns are written in that compared form.
 */
internal class VoiceGrammar(
    /** Words are separated by spaces; otherwise (Chinese, Japanese, Korean particles, Thai, Burmese) a word is found inside the text. */
    val spaced: Boolean = true,
    /** Vietnamese tells «lúc» (at) from «lục» (six) by the tone mark: its words keep them. Station names never do. */
    private val keepsLatinMarks: Boolean = false,
    today: List<String>,
    tomorrow: List<String>,
    dayAfterTomorrow: List<String> = emptyList(),
    /** Phrases with a day word inside that do not name that day («heute morgen» is this morning, «大后天» three days on). */
    notDays: List<String> = emptyList(),
    /** An hour said with these is a time of the afternoon or evening («alle 8 di sera», «午後8時»). */
    afternoon: List<String> = emptyList(),
    /** Phrases with an afternoon word inside that say something else (Vietnamese «một chiều» is one way). */
    notAfternoon: List<String> = emptyList(),
    returnTrip: List<String> = emptyList(),
    oneWay: List<String> = emptyList(),
    months: Map<String, Int> = emptyMap(),
    /** Group 1 the hour, group 2 the minutes or a word of [minuteWords], group 3 am or pm. */
    times: List<String>,
    /** «{M}» stands for the month names. */
    dates: List<DatePattern> = emptyList(),
    minuteWords: Map<String, Int> = emptyMap(),
    /** A word right after a time that changes it («alle 8 meno un quarto», «9時5分前»): the time is not known. */
    continuations: List<String> = emptyList(),
    /** A word right before the hour that moves it, in minutes (Hindi «साढ़े 9 बजे» is 9:30). */
    hourModifiers: Map<String, Int> = emptyMap(),
    /** «12/11» is the 12th of November; not read where the month comes first (Japanese, Korean, Chinese). */
    val readsSlashDates: Boolean = true,
    /** Years of the Thai calendar are 543 ahead. */
    private val eraOffset: Int = 0,
    fromBefore: List<String> = emptyList(),
    toBefore: List<String> = emptyList(),
    fromAfter: List<String> = emptyList(),
    toAfter: List<String> = emptyList(),
    /** Case endings written onto a station name (Turkish «Romadan»): the name is still found. */
    stationEndings: List<String> = emptyList()
) {
    fun text(value: String): String =
        VoiceText.normalize(value, foldLatinMarks = !keepsLatinMarks).let { if (spaced) it else VoiceText.joinNativeWords(it) }

    private fun words(list: List<String>) = list.map(::text).filter { it.isNotEmpty() }.distinct()
    private fun stationWords(list: List<String>) = list.map { VoiceText.normalize(it) }.filter { it.isNotEmpty() }.distinct()
    // NFKC like the text: Thai «ำ», for one, is compared as «ํา».
    private fun pattern(source: String) = Regex(Normalizer.normalize(source, Normalizer.Form.NFKC))

    val today = words(today)
    val tomorrow = words(tomorrow)
    val dayAfterTomorrow = words(dayAfterTomorrow)
    private val notDays = words(notDays)
    private val afternoon = words(afternoon)
    private val notAfternoon = words(notAfternoon)
    val returnTrip = words(returnTrip)
    val oneWay = words(oneWay)
    private val months = months.mapKeys { text(it.key) }
    private val monthAlternation = this.months.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    val times = times.map(::pattern)
    private val dates = dates.map { DateRule(pattern(it.pattern.replace("{M}", monthAlternation)), it.monthFirst) }
    val minuteWords = minuteWords.mapKeys { text(it.key) }
    private val continuations = words(continuations)
    private val hourModifiers = hourModifiers.mapKeys { text(it.key) }
    private val fromBefore = stationWords(fromBefore)
    private val toBefore = stationWords(toBefore)
    private val fromAfter = stationWords(fromAfter)
    private val toAfter = stationWords(toAfter)
    val stationEnding: String? = stationWords(stationEndings).takeIf { it.isNotEmpty() }
        ?.sortedByDescending { it.length }?.joinToString("|") { Regex.escape(it) }

    fun has(text: String, word: String): Boolean =
        if (spaced) Regex("(?:^|\\s)${Regex.escape(word)}(?=\\s|$)").containsMatchIn(text) else text.contains(word)

    fun hasAny(text: String, words: List<String>): Boolean = words.any { has(text, it) }

    private fun without(text: String, phrases: List<String>): String = phrases.fold(text) { rest, phrase ->
        if (spaced) rest.replace(Regex("(?:^|\\s)${Regex.escape(phrase)}(?=\\s|$)"), " ") else rest.replace(phrase, " ")
    }

    fun withoutNotDays(text: String): String = without(text, notDays)

    fun saysAfternoon(text: String): Boolean = hasAny(without(text, notAfternoon), afternoon)

    /** Whether what follows a time changes it: a word of [continuations], or a number where no minutes were said. */
    fun continues(rest: String, minutesSaid: Boolean): Boolean {
        val next = rest.trimStart()
        if (!minutesSaid && next.firstOrNull()?.isDigit() == true) return true
        return continuations.any { startsWithWord(next, it) }
    }

    fun modifierBefore(text: String, hourStart: Int): Int? {
        val before = text.substring(0, hourStart).trimEnd()
        return hourModifiers.entries.firstOrNull { (word, _) -> endsWithWord(before, word) }?.value
    }

    fun month(text: String): Int? = months[text] ?: text.toIntOrNull()

    fun year(text: String): Int? = text.toIntOrNull()?.let { if (eraOffset > 0 && it > 2400) it - eraOffset else it }

    fun dateRules(): List<DateRule> = dates

    /** What the words next to a station name in [stationText] say about it. */
    fun role(stationText: String, range: IntRange): StationRole? {
        val before = stationText.substring(0, range.first).trimEnd()
        val after = stationText.substring(range.last + 1).trimStart()
        val from = fromBefore.any { endsWithWord(before, it) } || fromAfter.any { startsWithWord(after, it) }
        val to = toBefore.any { endsWithWord(before, it) } || toAfter.any { startsWithWord(after, it) }
        return when {
            from && !to -> StationRole.FROM
            to && !from -> StationRole.TO
            else -> null
        }
    }

    private fun startsWithWord(text: String, word: String) =
        if (spaced) text == word || text.startsWith("$word ") else text.startsWith(word)

    private fun endsWithWord(text: String, word: String) =
        if (spaced) text == word || text.endsWith(" $word") else text.endsWith(word)

    class DateRule(val regex: Regex, val monthFirst: Boolean)
}

/** One grammar per language of the app; the recognizer is asked for that language, so the words never mix. */
internal object VoiceGrammars {
    private const val WESTERN_MINUTES = "(\\d{2}|mezza|mezzo|media|demie|un quarto|quarto|cuarto|quart)"
    private const val MERIDIEM = "(?:\\s*(am|a m|pm|p m))?"

    /**
     * Italian, English, German, French and Spanish share one table, as they did before the other languages had one:
     * their words were built and tested together and do not collide.
     */
    private val western = VoiceGrammar(
        today = listOf("oggi", "stasera", "stamattina", "today", "tonight", "heute", "aujourd'hui", "ce soir", "hoy"),
        tomorrow = listOf("domani", "tomorrow", "morgen", "demain", "mañana"),
        dayAfterTomorrow = listOf("dopodomani", "day after tomorrow", "übermorgen", "après-demain", "pasado mañana"),
        // "heute morgen" is this morning, "por la mañana" in the morning.
        notDays = listOf("heute morgen", "por la mañana", "de la mañana"),
        afternoon = listOf(
            "di sera", "della sera", "la sera", "stasera", "di pomeriggio", "del pomeriggio", "nel pomeriggio",
            "il pomeriggio", "di notte", "stanotte", "in the evening", "this evening", "tonight", "in the afternoon",
            "this afternoon", "at night", "abends", "am abend", "heute abend", "nachmittags", "am nachmittag",
            "heute nachmittag", "nachts", "du soir", "le soir", "ce soir", "de l'après-midi", "l'après-midi",
            "cet après-midi", "de la tarde", "por la tarde", "esta tarde", "de la noche", "por la noche", "esta noche"
        ),
        returnTrip = listOf(
            "andata e ritorno", "andata ritorno", "return trip", "round trip", "return journey", "and back",
            "hin und zurück", "hin und rückfahrt", "hin und retour", "aller-retour", "ida y vuelta"
        ),
        oneWay = listOf(
            "sola andata", "solo andata", "one way", "single journey", "einfache fahrt", "nur hinfahrt",
            "aller simple", "solo ida"
        ),
        months = mapOf(
            "gennaio" to 1, "febbraio" to 2, "marzo" to 3, "aprile" to 4, "maggio" to 5, "giugno" to 6,
            "luglio" to 7, "agosto" to 8, "settembre" to 9, "ottobre" to 10, "novembre" to 11, "dicembre" to 12,
            "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6,
            "july" to 7, "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12,
            "januar" to 1, "februar" to 2, "märz" to 3, "mai" to 5, "juni" to 6, "juli" to 7,
            "oktober" to 10, "dezember" to 12,
            "janvier" to 1, "février" to 2, "mars" to 3, "avril" to 4, "juin" to 6, "juillet" to 7,
            "août" to 8, "septembre" to 9, "octobre" to 10, "décembre" to 12,
            "enero" to 1, "febrero" to 2, "abril" to 4, "mayo" to 5, "junio" to 6, "julio" to 7,
            "septiembre" to 9, "setiembre" to 9, "octubre" to 10, "noviembre" to 11, "diciembre" to 12
        ),
        times = listOf(
            "(?:^|\\s)(?:alle|ore|per le|at|around|um|gegen|vers|a las|a la|sobre las|hacia las|a)\\s+(\\d{1,2})" +
                "(?:\\s*(?:uhr|heures|heure|horas|hours|h|o clock))?(?:\\s*(?:e|y|and|et)?\\s*$WESTERN_MINUTES)?$MERIDIEM(?=\\s|$)",
            "(?:^|\\s)(\\d{1,2})\\s*(?:uhr|heures|heure|h)(?:\\s*$WESTERN_MINUTES)?$MERIDIEM(?=\\s|$)"
        ),
        dates = listOf(
            DatePattern("(?:^|\\s)(\\d{1,2})(?:\\s?(?:st|nd|rd|th|er|o))?\\s+(?:de\\s+|of\\s+)?({M})(?:\\s+(?:de\\s+)?(\\d{4}))?(?=\\s|$)"),
            DatePattern("(?:^|\\s)({M})\\s+(?:the\\s+)?(\\d{1,2})(?:\\s?(?:st|nd|rd|th))?(?:\\s+(\\d{4}))?(?=\\s|$)", monthFirst = true)
        ),
        minuteWords = mapOf(
            "mezza" to 30, "mezzo" to 30, "media" to 30, "demie" to 30,
            "un quarto" to 15, "quarto" to 15, "cuarto" to 15, "quart" to 15
        ),
        // «alle 8 e tre quarti», «alle 9 meno 10», «a las 8 menos cuarto», «à 9 h moins le quart», «um 10 vor 8».
        continuations = listOf("e", "y", "and", "et", "und", "meno", "menos", "moins", "minus", "past", "vor"),
        fromBefore = listOf("da", "dal", "dalla", "dallo", "from", "von", "vom", "ab", "de", "du", "des", "desde"),
        toBefore = listOf(
            "a", "al", "alla", "allo", "per", "verso", "to", "nach", "zum", "zur", "bis", "à", "au", "vers", "pour",
            "hacia", "para", "hasta"
        )
    )

    // The languages below follow the example sentences of the companion app (planner.dictationDescription) and the
    // hints of this app (voice_feedback_unresolved_route), with the forms its audit found misread.

    private val portuguese = VoiceGrammar(
        today = listOf("hoje", "esta noite", "esta tarde"),
        tomorrow = listOf("amanhã"),
        dayAfterTomorrow = listOf("depois de amanhã"),
        afternoon = listOf("da tarde", "da noite", "à tarde", "à noite", "de tarde", "de noite", "esta tarde", "esta noite"),
        returnTrip = listOf("ida e volta"),
        oneWay = listOf("só ida", "somente ida", "apenas ida"),
        months = mapOf(
            "janeiro" to 1, "fevereiro" to 2, "março" to 3, "abril" to 4, "maio" to 5, "junho" to 6,
            "julho" to 7, "agosto" to 8, "setembro" to 9, "outubro" to 10, "novembro" to 11, "dezembro" to 12
        ),
        // «às 9:35», «às 21h30», «às 18h», «às 8 e meia».
        times = listOf(
            "(?:^|\\s)(?:as|a|pelas|por volta das)\\s(\\d{1,2})(?:\\s?(?:horas|hora|hs|h))?" +
                "(?:\\s(?:e\\s)?(\\d{2}|meia)(?:\\s(?:minutos|min))?)?(?=\\s|$)",
            "(?:^|\\s)(\\d{1,2})\\s?(?:horas|h)(?:\\s(?:e\\s)?(\\d{2}|meia))?(?=\\s|$)"
        ),
        dates = listOf(DatePattern("(?:^|\\s)(?:dia\\s)?(\\d{1,2})\\s(?:de\\s)?({M})(?:\\s(?:de\\s)?(\\d{4}))?(?=\\s|$)")),
        minuteWords = mapOf("meia" to 30),
        continuations = listOf("e", "menos"),
        fromBefore = listOf("de", "do", "da", "desde"),
        toBefore = listOf("para", "pra", "até", "ao", "a")
    )

    private val turkish = VoiceGrammar(
        today = listOf("bugün", "bu akşam"),
        tomorrow = listOf("yarın"),
        dayAfterTomorrow = listOf("yarından sonra", "öbür gün"),
        afternoon = listOf("akşam", "öğleden sonra"),
        returnTrip = listOf("gidiş dönüş"),
        oneWay = listOf("tek yön", "tek gidiş"),
        months = mapOf(
            "ocak" to 1, "şubat" to 2, "mart" to 3, "nisan" to 4, "mayıs" to 5, "haziran" to 6,
            "temmuz" to 7, "ağustos" to 8, "eylül" to 9, "ekim" to 10, "kasım" to 11, "aralık" to 12
        ),
        // «saat 9:35», «saat 18'de», «saat 8 buçukta», «akşam 8'de»; the case ending after the hour is read too.
        times = listOf(
            "(?:^|\\s)(?:saat|aksam|sabah|ogleden sonra)\\s(\\d{1,2})(?:\\s(\\d{2}|bucuk))?(?:\\s?(?:de|da|te|ta))?(?=\\s|$)"
        ),
        dates = listOf(DatePattern("(?:^|\\s)(\\d{1,2})\\s({M})(?:\\s(\\d{4}))?(?=\\s|$)")),
        minuteWords = mapOf("buçuk" to 30),
        // «saat 9'u çeyrek geçe», «saat 9'a 10 kala»: the quarter-past and to-the-hour forms are not read.
        continuations = listOf("çeyrek", "geçe", "kala", "u", "ü", "i", "ı", "yi", "a", "e", "ya", "ye"),
        fromAfter = listOf("dan", "den", "tan", "ten", "ndan", "nden"),
        toAfter = listOf("ya", "ye", "a", "e", "na", "ne"),
        stationEndings = listOf(
            "dan", "den", "tan", "ten", "ndan", "nden", "ya", "ye", "na", "ne", "a", "e", "da", "de", "ta", "te"
        )
    )

    private val vietnamese = VoiceGrammar(
        keepsLatinMarks = true,
        today = listOf("hôm nay", "tối nay", "chiều nay", "sáng nay"),
        tomorrow = listOf("ngày mai", "sáng mai", "chiều mai", "tối mai"),
        dayAfterTomorrow = listOf("ngày kia", "ngày mốt"),
        afternoon = listOf("chiều", "tối"),
        notAfternoon = listOf("một chiều", "chiều dài"),
        returnTrip = listOf("khứ hồi"),
        oneWay = listOf("một chiều"),
        // «lúc 9:35», «lúc 18 giờ», «lúc 9 giờ 35 phút», «lúc 8 giờ rưỡi», «9h35».
        times = listOf(
            "(?:^|\\s)(?:lúc|vào)\\s(\\d{1,2})(?:\\s?(?:giờ|h))?(?:\\s(\\d{2}|\\d(?=\\s?phút)|rưỡi)(?:\\s?phút)?)?(?=\\s|$)",
            "(?:^|\\s)(\\d{1,2})\\s?(?:giờ|h)(?:\\s(\\d{2}|\\d(?=\\s?phút)|rưỡi)(?:\\s?phút)?)?(?=\\s|$)"
        ),
        dates = listOf(DatePattern("(?:^|\\s)(?:ngày\\s)?(\\d{1,2})\\stháng\\s(\\d{1,2})(?:\\snăm\\s(\\d{4}))?(?=\\s|$)")),
        minuteWords = mapOf("rưỡi" to 30),
        // «9 giờ kém 15» is 8:45, «9 giờ lẻ 5» 9:05.
        continuations = listOf("kém", "lẻ"),
        fromBefore = listOf("từ"),
        toBefore = listOf("đến", "tới", "về")
    )

    private val indonesian = VoiceGrammar(
        today = listOf("hari ini", "malam ini", "sore ini", "nanti malam"),
        tomorrow = listOf("besok", "esok"),
        dayAfterTomorrow = listOf("lusa", "besok lusa"),
        afternoon = listOf("sore", "malam"),
        returnTrip = listOf("pulang pergi"),
        oneWay = listOf("sekali jalan", "satu arah"),
        months = mapOf(
            "januari" to 1, "februari" to 2, "maret" to 3, "april" to 4, "mei" to 5, "juni" to 6,
            "juli" to 7, "agustus" to 8, "september" to 9, "oktober" to 10, "november" to 11, "desember" to 12
        ),
        // «pukul 9.35», «pukul 18», «jam 18:00».
        times = listOf("(?:^|\\s)(?:pukul|jam)\\s(\\d{1,2})(?:\\s(\\d{2}))?(?=\\s|$)"),
        dates = listOf(DatePattern("(?:^|\\s)(?:tanggal\\s)?(\\d{1,2})\\s({M})(?:\\s(\\d{4}))?(?=\\s|$)")),
        // «jam 9 lewat 15», «jam 9 kurang 15».
        continuations = listOf("lewat", "kurang", "lebih"),
        fromBefore = listOf("dari"),
        toBefore = listOf("ke", "menuju", "sampai")
    )

    private val russian = VoiceGrammar(
        today = listOf("сегодня"),
        tomorrow = listOf("завтра"),
        dayAfterTomorrow = listOf("послезавтра"),
        afternoon = listOf("вечера", "вечером", "дня"),
        returnTrip = listOf("туда и обратно", "туда обратно"),
        oneWay = listOf("в одну сторону", "только туда"),
        months = mapOf(
            "января" to 1, "февраля" to 2, "марта" to 3, "апреля" to 4, "мая" to 5, "июня" to 6,
            "июля" to 7, "августа" to 8, "сентября" to 9, "октября" to 10, "ноября" to 11, "декабря" to 12
        ),
        // «в 9:35», «в 18», «в 8 вечера», «в 9 часов 35 минут».
        times = listOf(
            "(?:^|\\s)(?:в|к|около)\\s(\\d{1,2})(?:\\s?(?:часов|часа|час|ч))?" +
                "(?:\\s(\\d{2}|\\d(?=\\s?мин))(?:\\s?(?:минут|минуты|минута|мин))?)?(?=\\s|$)",
            "(?:^|\\s)(\\d{1,2})\\s?(?:часов|часа|час|ч)(?:\\s(\\d{2}|\\d(?=\\s?мин))(?:\\s?(?:минут|минуты|минута|мин))?)?(?=\\s|$)"
        ),
        dates = listOf(DatePattern("(?:^|\\s)(\\d{1,2})\\s({M})(?:\\s(\\d{4}))?(?=\\s|$)")),
        continuations = listOf("без"),
        fromBefore = listOf("из", "от", "с"),
        toBefore = listOf("в", "до", "на")
    )

    private val arabic = VoiceGrammar(
        today = listOf("اليوم", "الليلة", "هذا المساء"),
        tomorrow = listOf("غدًا", "غدا", "بكرة", "بكرا"),
        dayAfterTomorrow = listOf("بعد غد", "بعد غدا", "بعد بكرة"),
        afternoon = listOf("مساءً", "مساءا", "مساء", "بعد الظهر", "الليلة", "هذا المساء"),
        returnTrip = listOf("ذهاب وعودة", "ذهاب وإياب", "ذهابا وإيابا"),
        oneWay = listOf("ذهاب فقط", "اتجاه واحد"),
        months = mapOf(
            "يناير" to 1, "فبراير" to 2, "مارس" to 3, "أبريل" to 4, "مايو" to 5, "يونيو" to 6,
            "يوليو" to 7, "أغسطس" to 8, "سبتمبر" to 9, "أكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12
        ),
        // «الساعة 9:35», «الساعة 18», «الساعة 9 والنصف», «الساعة 9 و35 دقيقة».
        times = listOf(
            "(?:^|\\s)الساعة\\s(\\d{1,2})(?:\\s(?:و\\s?)?(\\d{2}|\\d(?=\\s?دقيق)|النصف|نصف|الربع|ربع)" +
                "(?:\\s?(?:دقيقة|دقائق))?)?(?=\\s|$)"
        ),
        dates = listOf(DatePattern("(?:^|\\s)(\\d{1,2})\\s({M})(?:\\s(\\d{4}))?(?=\\s|$)")),
        minuteWords = mapOf("النصف" to 30, "نصف" to 30, "الربع" to 15, "ربع" to 15),
        // «التاسعة إلا ربع» is 8:45.
        continuations = listOf("إلا"),
        fromBefore = listOf("من"),
        toBefore = listOf("إلى", "حتى")
    )

    private val hindi = VoiceGrammar(
        today = listOf("आज"),
        tomorrow = listOf("कल"),
        dayAfterTomorrow = listOf("परसों"),
        afternoon = listOf("शाम", "दोपहर"),
        returnTrip = listOf("आना जाना", "राउंड ट्रिप", "वापसी सहित"),
        oneWay = listOf("एक तरफ़ा", "एक तरफा", "वन वे"),
        months = mapOf(
            "जनवरी" to 1, "फ़रवरी" to 2, "फरवरी" to 2, "मार्च" to 3, "अप्रैल" to 4, "मई" to 5, "जून" to 6,
            "जुलाई" to 7, "अगस्त" to 8, "सितंबर" to 9, "सितम्बर" to 9, "अक्टूबर" to 10, "नवंबर" to 11,
            "नवम्बर" to 11, "दिसंबर" to 12, "दिसम्बर" to 12
        ),
        // «9:35 बजे», «18 बजे», «9 बजकर 35 मिनट».
        times = listOf(
            "(?:^|\\s)(\\d{1,2})(?:\\s(\\d{2}))?\\s?बजे(?=\\s|$)",
            "(?:^|\\s)(\\d{1,2})\\s?बजकर\\s(\\d{1,2})\\s?मिनट(?=\\s|$)"
        ),
        dates = listOf(DatePattern("(?:^|\\s)(\\d{1,2})\\s({M})(?:\\s(\\d{4}))?(?=\\s|$)")),
        // «साढ़े 9 बजे» 9:30, «सवा 9 बजे» 9:15, «पौने 9 बजे» 8:45.
        hourModifiers = mapOf("साढ़े" to 30, "सवा" to 15, "पौने" to -15),
        fromAfter = listOf("से"),
        toAfter = listOf("तक", "के लिए", "को")
    )

    private val japanese = VoiceGrammar(
        spaced = false,
        today = listOf("今日", "きょう", "本日", "今夜", "今晩"),
        tomorrow = listOf("明日", "あした"),
        dayAfterTomorrow = listOf("明後日", "あさって"),
        notDays = listOf("明日香"),
        afternoon = listOf("午後", "夕方", "今夜", "今晩"),
        returnTrip = listOf("往復"),
        oneWay = listOf("片道"),
        // «9時35分», «18 時», «8時半»; «2時間» is two hours.
        times = listOf("(?<!\\d)(\\d{1,2}) ?時(?!間)(?: ?(\\d{1,2}(?= ?分)|半)(?: ?分)?)?"),
        dates = listOf(DatePattern("(?<!\\d)(\\d{1,2}) ?月 ?(\\d{1,2}) ?日", monthFirst = true)),
        minuteWords = mapOf("半" to 30),
        // «9時5分前» is 8:55.
        continuations = listOf("前"),
        readsSlashDates = false,
        fromAfter = listOf("から", "発"),
        toAfter = listOf("まで", "行き", "へ", "着")
    )

    private val korean = VoiceGrammar(
        // Words carry their particles («내일은», «9시에»): a word is found inside the text.
        spaced = false,
        today = listOf("오늘"),
        tomorrow = listOf("내일"),
        dayAfterTomorrow = listOf("모레", "내일모레"),
        afternoon = listOf("오후", "저녁"),
        returnTrip = listOf("왕복"),
        oneWay = listOf("편도"),
        // «9시 35분», «18시», «8시 반»; «2시간» is two hours.
        times = listOf("(?<!\\d)(\\d{1,2}) ?시(?!간)(?: ?(\\d{1,2}(?= ?분)|반)(?: ?분)?)?"),
        dates = listOf(DatePattern("(?<!\\d)(\\d{1,2}) ?월 ?(\\d{1,2}) ?일", monthFirst = true)),
        minuteWords = mapOf("반" to 30),
        // «9시 5분 전» is 8:55.
        continuations = listOf("전"),
        readsSlashDates = false,
        fromAfter = listOf("에서", "출발"),
        toAfter = listOf("까지", "으로", "로", "행")
    )

    private val chinese = VoiceGrammar(
        spaced = false,
        today = listOf("今天", "今晚", "今日"),
        tomorrow = listOf("明天", "明日", "明早", "明晚"),
        dayAfterTomorrow = listOf("后天", "後天"),
        notDays = listOf("大后天", "大後天"),
        afternoon = listOf("下午", "晚上", "今晚", "傍晚", "明晚"),
        returnTrip = listOf("往返", "来回"),
        oneWay = listOf("单程"),
        // «9点35分», «18 点», «8点半», «8点一刻»; «2小时» is two hours.
        times = listOf("(?<!\\d)(\\d{1,2}) ?(?:点|點|时)(?: ?(\\d{2}|\\d(?= ?分)|半|一刻|三刻)(?: ?分)?)?"),
        dates = listOf(DatePattern("(?<!\\d)(\\d{1,2}) ?月 ?(\\d{1,2}) ?(?:日|号|號)", monthFirst = true)),
        minuteWords = mapOf("半" to 30, "一刻" to 15, "三刻" to 45),
        // «9点差5分» is 8:55.
        continuations = listOf("差", "前"),
        readsSlashDates = false,
        fromBefore = listOf("从", "從", "由"),
        toBefore = listOf("到", "去", "至", "往")
    )

    private val thai = VoiceGrammar(
        spaced = false,
        today = listOf("วันนี้", "คืนนี้"),
        tomorrow = listOf("พรุ่งนี้"),
        dayAfterTomorrow = listOf("มะรืนนี้", "มะรืน"),
        afternoon = listOf("บ่าย", "เย็น", "ค่ำ", "คืนนี้"),
        returnTrip = listOf("ไปกลับ", "ไป-กลับ"),
        oneWay = listOf("เที่ยวเดียว"),
        months = mapOf(
            "มกราคม" to 1, "กุมภาพันธ์" to 2, "มีนาคม" to 3, "เมษายน" to 4, "พฤษภาคม" to 5, "มิถุนายน" to 6,
            "กรกฎาคม" to 7, "สิงหาคม" to 8, "กันยายน" to 9, "ตุลาคม" to 10, "พฤศจิกายน" to 11, "ธันวาคม" to 12
        ),
        eraOffset = 543,
        // «18 นาฬิกา», «6 โมงเย็น», «บ่าย 2 โมง», «เวลา 9:35», «18.00 น.».
        times = listOf(
            "(?<!\\d)(\\d{1,2}) ?นาฬิกา(?: ?(\\d{2}|\\d(?= ?นาที)|ครึ่ง)(?: ?นาที)?)?",
            "(?<!\\d)(\\d{1,2}) ?โมง(?: ?(\\d{2}|\\d(?= ?นาที)|ครึ่ง)(?: ?นาที)?)?",
            "เวลา ?(\\d{1,2})(?: (\\d{2}))?(?!\\d)",
            "(?<!\\d)(\\d{1,2}) (\\d{2}) ?น(?!า)"
        ),
        dates = listOf(DatePattern("(?<!\\d)(\\d{1,2}) ?({M})(?: ?(\\d{4}))?")),
        minuteWords = mapOf("ครึ่ง" to 30),
        // «ใช้เวลา 2 ชั่วโมง» takes two hours.
        continuations = listOf("ชั่วโมง", "นาที"),
        fromBefore = listOf("จาก"),
        toBefore = listOf("ไป", "ถึง", "ไปยัง", "ไปที่")
    )

    private val burmese = VoiceGrammar(
        spaced = false,
        today = listOf("ယနေ့", "ဒီနေ့"),
        tomorrow = listOf("မနက်ဖြန်", "နက်ဖြန်"),
        dayAfterTomorrow = listOf("သန်ဘက်ခါ"),
        afternoon = listOf("ညနေ"),
        returnTrip = listOf("အသွားအပြန်"),
        oneWay = listOf("အသွားသာ"),
        // «9:35», «18 နာရီ», «9 နာရီ 35 မိနစ်», «9 နာရီခွဲ».
        times = listOf("(?<!\\d)(\\d{1,2}) ?နာရီ(?: ?(\\d{2}|\\d(?= ?မိနစ်)|ခွဲ)(?: ?မိနစ်)?)?"),
        minuteWords = mapOf("ခွဲ" to 30),
        // «2 နာရီကြာ» takes two hours.
        continuations = listOf("ကြာ", "ထိုး"),
        fromAfter = listOf("မှ", "ကနေ"),
        toAfter = listOf("သို့", "ကို", "အထိ")
    )

    /** The grammar of a language code ("in" is Indonesian on Android), or null where the app knows no words. */
    fun forLanguage(language: String): VoiceGrammar? = when (language.lowercase(Locale.ROOT)) {
        "it", "en", "de", "fr", "es" -> western
        "pt" -> portuguese
        "tr" -> turkish
        "vi" -> vietnamese
        "in", "id" -> indonesian
        "ru" -> russian
        "ar" -> arabic
        "hi" -> hindi
        "ja" -> japanese
        "ko" -> korean
        "my" -> burmese
        "th" -> thai
        "zh" -> chinese
        else -> null
    }

    /** The grammar of the five first languages, for callers that do not say the language. */
    val default: VoiceGrammar get() = western
}
