package it.binario.app

import it.binario.app.data.Station
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The dictation sentences of the frozen companion app (its tests and the corrections of its audit), read by the
 * Android parser in the language the recognizer was asked for.
 */
class PlannerVoiceDictationFormsTest {
    private val ostiense = Station("roma-ostiense", "Roma Ostiense", "Roma")
    private val sanPietro = Station("roma-san-pietro", "Roma San Pietro", "Roma")
    private val termini = Station("roma-termini", "Roma Termini", "Roma")
    private val novella = Station("firenze-smn", "Firenze Santa Maria Novella", "Firenze")
    private val centrale = Station("milano-centrale", "Milano Centrale", "Milano")
    private val stations = listOf(ostiense, sanPietro, termini, novella, centrale)
    private val today = LocalDate.of(2030, 1, 2)

    private fun route(spoken: String, language: String, day: LocalDate = today) =
        PlannerVoiceParser.review(spoken, stations, DictationTarget.ROUTE, day, language)

    private fun time(spoken: String, language: String): LocalTime? = route(spoken, language).input.time

    private fun assertOstienseToSanPietro(spoken: String, language: String, at: LocalTime = LocalTime.of(9, 35)) {
        val review = route(spoken, language)
        assertEquals("$language: $spoken", DictationReviewStatus.CONFIRMED, review.status)
        assertEquals("$language: $spoken", ostiense, review.input.origin)
        assertEquals("$language: $spoken", sanPietro, review.input.destination)
        assertEquals("$language: $spoken", today.plusDays(1), review.input.date)
        assertEquals("$language: $spoken", at, review.input.time)
    }

    private val westernPhrases = mapOf(
        "it" to "Da Roma Ostiense a Roma San Pietro domani alle {h}:{m}",
        "en" to "From Roma Ostiense to Roma San Pietro tomorrow at {h}:{m}",
        "de" to "Von Roma Ostiense nach Roma San Pietro morgen um {h}:{m}",
        "fr" to "De Roma Ostiense à Roma San Pietro demain à {h}:{m}",
        "es" to "De Roma Ostiense a Roma San Pietro mañana a las {h}:{m}"
    )

    private val eveningTimes = listOf("20" to "15", "21" to "40", "22" to "05", "23" to "59", "20" to "00")

    private fun assertTemplates(templates: Map<String, String>) = templates.forEach { (language, template) ->
        assertOstienseToSanPietro(template.replace("{h}", "9").replace("{m}", "35"), language)
        eveningTimes.forEach { (hour, minute) ->
            val spoken = template.replace("{h}", hour).replace("{m}", minute)
            assertOstienseToSanPietro(spoken, language, LocalTime.of(hour.toInt(), minute.toInt()))
        }
    }

    @Test
    fun `the five first languages read the companion sentences and the evening hours`() {
        assertTemplates(westernPhrases)
    }

    private val latinAndCyrillicPhrases = mapOf(
        "pt" to "De Roma Ostiense para Roma San Pietro amanhã às {h}:{m}",
        "ru" to "От Roma Ostiense до Roma San Pietro завтра в {h}:{m}",
        "tr" to "Roma Ostiense'den Roma San Pietro'ya yarın saat {h}:{m}",
        "vi" to "Từ Roma Ostiense đến Roma San Pietro ngày mai lúc {h}:{m}",
        "in" to "Dari Roma Ostiense ke Roma San Pietro besok pukul {h}.{m}"
    )

    @Test
    fun `Portuguese, Russian, Turkish, Vietnamese and Indonesian read the companion sentences and the evening hours`() {
        assertTemplates(latinAndCyrillicPhrases)
        // The companion app's own spelling of Indonesian is "id".
        assertOstienseToSanPietro("Dari Roma Ostiense ke Roma San Pietro besok pukul 9.35", "id")
        assertEquals(LocalTime.of(21, 30), time("De Roma Termini para Milano Centrale amanhã às 21h30", "pt"))
        assertEquals(LocalTime.of(8, 30), time("De Roma Termini para Milano Centrale amanhã às 8 e meia", "pt"))
        assertEquals(LocalTime.of(20, 0), time("Из Roma Termini в Milano Centrale завтра в 8 вечера", "ru"))
        assertEquals(LocalTime.of(20, 0), time("Roma Termini'den Milano Centrale'ye yarın akşam 8'de", "tr"))
        assertEquals(LocalTime.of(8, 30), time("Roma Termini'den Milano Centrale'ye yarın saat 8 buçukta", "tr"))
        assertEquals(LocalTime.of(20, 30), time("Từ Roma Termini đến Milano Centrale lúc 8 giờ rưỡi tối", "vi"))
        assertEquals(LocalTime.of(20, 0), time("Dari Roma Termini ke Milano Centrale pukul 8 malam", "in"))
    }

    @Test
    fun `day words of the new languages, and words that must not fire inside other words`() {
        val prefix = mapOf(
            "pt" to "De Roma Termini para Milano Centrale",
            "ru" to "Из Roma Termini в Milano Centrale",
            "tr" to "Roma Termini'den Milano Centrale'ye",
            "vi" to "Từ Roma Termini đến Milano Centrale",
            "in" to "Dari Roma Termini ke Milano Centrale"
        )
        fun day(language: String, words: String) = route("${prefix.getValue(language)} $words", language).input.date
        assertEquals(today.plusDays(2), day("pt", "depois de amanhã às 9"))
        assertEquals(today, day("pt", "hoje às 9"))
        assertEquals(today.plusDays(2), day("ru", "послезавтра в 9"))
        assertNull(day("ru", "после завтрака"))
        assertEquals(today.plusDays(2), day("tr", "öbür gün saat 9"))
        assertNull(day("tr", "bugünlerde"))
        assertEquals(today.plusDays(2), day("vi", "ngày kia lúc 9 giờ"))
        assertEquals(today.plusDays(2), day("in", "lusa pukul 9"))
        assertEquals(LocalDate.of(2030, 11, 12), day("pt", "dia 12 de novembro"))
        assertEquals(LocalDate.of(2030, 11, 12), day("tr", "12 Kasım'da"))
        assertEquals(LocalDate.of(2030, 11, 12), day("vi", "ngày 12 tháng 11"))
        assertEquals(LocalDate.of(2030, 11, 12), day("ru", "12 ноября"))
        // «2 saatlik» is two hours long; «lục» (six) is not «lúc» (at); «semalam» (last night) is not «malam».
        assertNull(time("${prefix.getValue("tr")} 2 saatlik yolculuk", "tr"))
        assertNull(time("${prefix.getValue("vi")} lục 9", "vi"))
        assertEquals(LocalTime.of(8, 0), time("${prefix.getValue("in")} pukul 8 semalam", "in"))
        // «một chiều» is one way, not an afternoon.
        val oneWay = route("${prefix.getValue("vi")} lúc 8 giờ một chiều", "vi")
        assertEquals(LocalTime.of(8, 0), oneWay.input.time)
        assertEquals(false, oneWay.input.returnJourney)
        assertEquals(true, route("${prefix.getValue("tr")} gidiş dönüş", "tr").input.returnJourney)
        // Quarter-to forms are not read: no time rather than a wrong one.
        assertNull(time("${prefix.getValue("tr")} saat 9'a 10 kala", "tr"))
        assertNull(time("${prefix.getValue("vi")} lúc 9 giờ kém 15", "vi"))
        assertNull(time("${prefix.getValue("in")} jam 9 kurang 15", "in"))
    }

    @Test
    fun `Turkish case endings on station names`() {
        val two = listOf(centrale, termini)
        fun turkish(spoken: String) = PlannerVoiceParser.review(spoken, two, DictationTarget.ROUTE, today, "tr")
        val apostrophe = turkish("Milano'dan Roma'ya yarın saat 9")
        assertEquals(DictationReviewStatus.CONFIRMED, apostrophe.status)
        assertEquals(centrale, apostrophe.input.origin)
        assertEquals(termini, apostrophe.input.destination)
        val glued = turkish("Milanodan Romaya yarın saat 9")
        assertEquals(centrale, glued.input.origin)
        assertEquals(termini, glued.input.destination)
        // The endings, not the order, say the origin.
        assertEquals(centrale, turkish("Roma'ya Milano'dan yarın saat 9").input.origin)
    }

    private val otherScriptPhrases = mapOf(
        "ar" to "من Roma Ostiense إلى Roma San Pietro غدًا الساعة {h}:{m}",
        "hi" to "Roma Ostiense से Roma San Pietro कल {h}:{m} बजे",
        "ja" to "Roma Ostiense から Roma San Pietro まで 明日 {h}時{m}分",
        "ko" to "Roma Ostiense에서 Roma San Pietro까지 내일 {h}시 {m}분",
        "my" to "Roma Ostiense မှ Roma San Pietro သို့ မနက်ဖြန် {h}:{m}",
        "th" to "จาก Roma Ostiense ไป Roma San Pietro พรุ่งนี้ เวลา {h}:{m}",
        "zh" to "从 Roma Ostiense 到 Roma San Pietro 明天 {h}点{m}分"
    )

    private val otherScriptPrefix = mapOf(
        "ar" to "من Roma Termini إلى Milano Centrale غدًا",
        "hi" to "Roma Termini से Milano Centrale कल",
        "ja" to "Roma Termini から Milano Centrale まで 明日",
        "ko" to "Roma Termini에서 Milano Centrale까지 내일",
        "my" to "Roma Termini မှ Milano Centrale သို့ မနက်ဖြန်",
        "th" to "จาก Roma Termini ไป Milano Centrale พรุ่งนี้",
        "zh" to "从 Roma Termini 到 Milano Centrale 明天"
    )

    private fun otherScript(language: String, words: String) = route("${otherScriptPrefix.getValue(language)} $words", language)

    @Test
    fun `Arabic, Hindi, Japanese, Korean, Burmese, Thai and Chinese read the companion sentences and the evening hours`() {
        assertTemplates(otherScriptPhrases)
        // The interface examples of the companion app write the hour with a colon.
        assertOstienseToSanPietro("Roma Ostiense から Roma San Pietro まで 明日 09:35", "ja")
        assertOstienseToSanPietro("Roma Ostiense에서 Roma San Pietro까지 내일 09:35", "ko")
        assertOstienseToSanPietro("从 Roma Ostiense 到 Roma San Pietro 明天 09:35", "zh")
    }

    @Test
    fun `afternoon markers, half hours and native digits`() {
        assertEquals(LocalTime.of(20, 0), otherScript("ja", "午後8時").input.time)
        assertEquals(LocalTime.of(20, 0), otherScript("ko", "오후 8시").input.time)
        assertEquals(LocalTime.of(20, 0), otherScript("zh", "下午8点").input.time)
        assertEquals(LocalTime.of(8, 30), otherScript("ja", "8時半").input.time)
        assertEquals(LocalTime.of(8, 30), otherScript("zh", "8点半").input.time)
        assertEquals(LocalTime.of(8, 30), otherScript("ko", "8시 반").input.time)
        assertEquals(LocalTime.of(20, 15), otherScript("ar", "الساعة ٢٠:١٥").input.time)
        assertEquals(LocalTime.of(20, 15), otherScript("hi", "२०:१५ बजे").input.time)
        assertEquals(LocalTime.of(20, 15), otherScript("my", "၂၀:၁၅").input.time)
        assertEquals(LocalTime.of(20, 15), otherScript("th", "๒๐:๑๕").input.time)
        assertEquals(LocalTime.of(9, 30), otherScript("ar", "الساعة 9 والنصف").input.time)
        assertEquals(LocalTime.of(9, 30), otherScript("hi", "साढ़े 9 बजे").input.time)
        assertEquals(LocalTime.of(8, 45), otherScript("hi", "पौने 9 बजे").input.time)
        assertEquals(LocalTime.of(18, 30), otherScript("hi", "शाम साढ़े 6 बजे").input.time)
        assertEquals(LocalTime.of(18, 0), otherScript("th", "6 โมงเย็น").input.time)
        assertEquals(LocalTime.of(14, 0), otherScript("th", "บ่าย 2 โมง").input.time)
        assertEquals(LocalTime.of(9, 30), otherScript("my", "9 နာရီခွဲ").input.time)
        // Before-the-hour forms the grammar does not read leave the time empty.
        assertNull(otherScript("ja", "9時5分前").input.time)
        assertNull(otherScript("ko", "9시 5분 전").input.time)
    }

    @Test
    fun `dates and words that must not fire inside other words, other scripts`() {
        fun day(language: String, words: String) =
            route(otherScriptPrefix.getValue(language).substringBeforeLast(' ') + " " + words, language).input.date
        assertEquals(LocalDate.of(2030, 11, 12), day("ja", "11月12日 9時"))
        assertEquals(LocalDate.of(2030, 11, 12), day("ko", "11월 12일 9시"))
        assertEquals(LocalDate.of(2030, 11, 12), day("zh", "11月12日 9点"))
        assertEquals(LocalDate.of(2030, 11, 12), day("hi", "12 नवंबर 9 बजे"))
        assertEquals(LocalDate.of(2030, 11, 12), day("ar", "12 نوفمبر الساعة 9"))
        // The Thai year counts from 543 BCE.
        assertEquals(LocalDate.of(2030, 11, 12), day("th", "12/11/2573"))
        assertEquals(LocalDate.of(2030, 11, 12), day("th", "12 พฤศจิกายน"))
        // Japanese, Korean and Chinese write the month first: «12/11» is not read.
        assertNull(day("ja", "12/11"))
        assertEquals(today.plusDays(2), day("ja", "明後日"))
        assertEquals(today.plusDays(2), day("ko", "모레"))
        assertEquals(today.plusDays(2), day("zh", "后天"))
        assertEquals(today.plusDays(2), day("ar", "بعد غد"))
        assertEquals(today.plusDays(2), day("hi", "परसों"))
        assertEquals(today.plusDays(2), day("th", "มะรืนนี้"))
        // «الغداء» is lunch, «कलकत्ता» Calcutta, «明日香» a name, «大后天» three days on.
        assertNull(day("ar", "بعد الغداء"))
        assertNull(day("hi", "कलकत्ता"))
        assertNull(day("ja", "明日香"))
        assertNull(day("zh", "大后天"))
        // «2時間», «2시간», «2小时», «2 ชั่วโมง», «2 နာရီကြာ» are durations.
        assertNull(otherScript("ja", "2時間").input.time)
        assertNull(otherScript("ko", "2시간").input.time)
        assertNull(otherScript("zh", "2小时").input.time)
        assertNull(otherScript("th", "ใช้เวลา 2 ชั่วโมง").input.time)
        assertNull(otherScript("my", "၂ နာရီကြာ").input.time)
    }

    @Test
    fun `postpositions and prepositions of the other scripts say which station is the origin`() {
        assertEquals(termini, route("Milano Centrale まで Roma Termini から 明日 9時", "ja").input.origin)
        assertEquals(termini, route("Milano Centrale까지 Roma Termini에서 내일 9시", "ko").input.origin)
        assertEquals(termini, route("到 Milano Centrale 从 Roma Termini 明天 9点", "zh").input.origin)
        assertEquals(termini, route("إلى Milano Centrale من Roma Termini غدًا الساعة 9", "ar").input.origin)
        assertEquals(termini, route("Milano Centrale तक Roma Termini से कल 9 बजे", "hi").input.origin)
        assertEquals(termini, route("Milano Centrale သို့ Roma Termini မှ မနက်ဖြန် 9:00", "my").input.origin)
        assertEquals(termini, route("ไป Milano Centrale จาก Roma Termini พรุ่งนี้ 9 นาฬิกา", "th").input.origin)
    }

    @Test
    fun `edge times are a time or nothing, never a shortened hour`() {
        assertEquals(LocalTime.of(21, 0), time("Da Roma Termini a Milano Centrale domani alle 21", "it"))
        assertNull(time("Da Roma Termini a Milano Centrale domani alle 24:00", "it"))
        assertNull(time("Von Roma Termini nach Milano Centrale morgen um 25", "de"))
        assertEquals(LocalTime.of(20, 15), time("De Roma Termini à Milano Centrale demain à 20h15", "fr"))
    }

    @Test
    fun `English am and pm`() {
        fun at(said: String) = time("From Roma Termini to Milano Centrale tomorrow at $said", "en")
        assertEquals(LocalTime.of(20, 0), at("8pm"))
        assertEquals(LocalTime.of(20, 0), at("8 pm"))
        assertEquals(LocalTime.of(20, 30), at("8:30 p.m."))
        assertEquals(LocalTime.of(23, 59), at("11:59 PM"))
        assertEquals(LocalTime.of(12, 0), at("12pm"))
        assertEquals(LocalTime.of(0, 0), at("12am"))
        assertEquals(LocalTime.of(8, 0), at("8am"))
        assertEquals(LocalTime.of(8, 0), at("8"))
        assertNull(at("13pm"))
        val noDay = route("From Roma Termini to Milano Centrale at 8pm", "en")
        assertEquals(termini, noDay.input.origin)
        assertEquals(centrale, noDay.input.destination)
        assertNull(noDay.input.date)
        assertEquals(LocalTime.of(20, 0), noDay.input.time)
    }

    @Test
    fun `the forms the companion app misread give the right time`() {
        val prefix = "Da Roma Termini a Milano Centrale domani"
        assertEquals(LocalTime.of(7, 45), time("$prefix alle 7.45", "it"))
        assertEquals(LocalTime.of(8, 30), time("$prefix alle 8 e mezza", "it"))
        assertEquals(LocalTime.of(20, 30), time("From Roma Termini to Milano Centrale at 8.30pm", "en"))
        assertEquals(LocalTime.of(8, 30), time("Von Roma Termini nach Milano Centrale um 8.30", "de"))
        assertEquals(LocalTime.of(20, 15), time("Von Roma Termini nach Milano Centrale um 20 Uhr 15", "de"))
        assertEquals(LocalTime.of(20, 15), time("De Roma Termini à Milano Centrale à 20 h 15", "fr"))
        assertEquals(LocalTime.of(9, 0), time("$prefix alle ore 9", "it"))
        val dated = route("Da Roma Termini a Milano Centrale il 12/11 alle 9:30", "it", LocalDate.of(2026, 11, 11))
        assertEquals(LocalDate.of(2026, 11, 12), dated.input.date)
        assertEquals(LocalTime.of(9, 30), dated.input.time)
    }

    @Test
    fun `an hour followed by words the grammar does not understand leaves the time empty`() {
        val prefix = "Da Roma Termini a Milano Centrale domani"
        assertNull(time("$prefix alle 8 meno un quarto", "it"))
        assertNull(time("$prefix alle 8 e tre quarti", "it"))
        assertNull(time("$prefix alle 8 e 5", "it"))
        assertNull(time("De Roma Termini a Milano Centrale mañana a las 8 menos cuarto", "es"))
        assertNull(time("De Roma Termini à Milano Centrale demain à 9 h moins le quart", "fr"))
        // The day is still read.
        assertEquals(today.plusDays(1), route("$prefix alle 8 meno un quarto", "it").input.date)
    }

    @Test
    fun `a sentence with no route, day or time applies nothing`() {
        val review = route("ritardo del treno", "it")
        assertEquals(DictationReviewStatus.UNRESOLVED, review.status)
        assertEquals(DictatedPlannerInput(), review.input)
    }

    @Test
    fun `today and tomorrow are days of Italy, whatever the zone of the phone`() {
        val newYork = Clock.fixed(Instant.parse("2026-11-11T23:30:00Z"), ZoneId.of("America/New_York"))
        val italian = PlannerVoiceParser.italianToday(newYork)
        assertEquals(LocalDate.of(2026, 11, 12), italian)
        assertEquals(LocalDate.of(2026, 11, 12), route("Da Roma Termini a Milano Centrale oggi alle 9", "it", italian).input.date)
        assertEquals(LocalDate.of(2026, 11, 13), route("Da Roma Termini a Milano Centrale domani alle 9", "it", italian).input.date)
    }

    @Test
    fun `the words around the stations say which is the origin`() {
        val english = route("to Milano Centrale from Roma Termini tomorrow at 9", "en")
        assertEquals(termini, english.input.origin)
        assertEquals(centrale, english.input.destination)
        assertEquals(termini, route("Per Milano Centrale da Roma Termini domani alle 9", "it").input.origin)
        // Without such words, or with contradicting ones, the first station said is the origin.
        assertEquals(termini, route("Roma Termini Milano Centrale domani alle 9", "it").input.origin)
        assertEquals(termini, route("Da Roma Termini a Milano Centrale domani alle 9", "it").input.origin)
    }

    @Test
    fun `text is compared in every script with ASCII digits`() {
        assertEquals("roma 9 35", VoiceText.normalize("Ｒｏｍａ ９：３５"))
        assertEquals("20 15 20 15 20 15 20 15", VoiceText.normalize("٢٠:١٥ २०:१५ ၂၀:၁၅ ๒๐:๑๕"))
        assertEquals("manana yarin tu den strasse", VoiceText.normalize("Mañana YARIN Từ đến Straße"))
        assertEquals("lúc đến", VoiceText.normalize("Lúc ĐẾN", foldLatinMarks = false))
        // The marks of other scripts are part of the letters; the optional Arabic vowel signs are not.
        assertEquals("कल 9 बजे", VoiceText.normalize("कल 9 बजे।"))
        assertEquals("завтрак й", VoiceText.normalize("Завтрак Й"))
        assertEquals("غدا الى", VoiceText.normalize("غدًا إلى"))
        // A space between two scripts.
        assertEquals("9 時 35 分", VoiceText.normalize("9時35分"))
        assertEquals("roma san pietro 까지 내일 9 시", VoiceText.normalize("Roma San Pietro까지 내일 9시"))
        assertEquals("พรุ่งนี้เวลา 9 roma", VoiceText.joinNativeWords("พรุ่งนี้ เวลา 9 roma"))
    }
}
