package it.binario.app.notifications

import it.binario.app.data.RailOperator
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.ServiceStatus
import it.binario.app.data.notices.TrainNoticeContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainAlertWatchTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val today = LocalDate.of(2026, 11, 2)
    private fun at(time: String, date: LocalDate = today): Instant = LocalDateTime.of(date, java.time.LocalTime.parse(time)).atZone(zone).toInstant()
    private val subscribed = at("06:00").minusSeconds(86_400)

    private fun subscription(number: String, departure: String) =
        TrainAlertSubscription(number, number, "S01700", RailOperator.TRENITALIA_REGIONALE, departure, "REG $number", subscribed)

    @Test fun `a favourite is checked from four hours before to three hours after its departure, never in the quiet hours`() {
        val morning = subscription("2153", "08:30")
        val early = subscription("2001", "06:10")
        val evening = subscription("2399", "21:15")

        assertEquals(at("07:00")..at("11:30"), TrainAlertWatch.window(morning, today, emptyList(), zone))
        assertEquals(at("07:00")..at("09:10"), TrainAlertWatch.window(early, today, emptyList(), zone))
        assertEquals(at("17:15")..at("22:00"), TrainAlertWatch.window(evening, today, emptyList(), zone))
        assertEquals(at("07:00")..at("22:00"), TrainAlertWatch.window(subscription("4", ""), today, emptyList(), zone))
        assertTrue(TrainAlertWatch.due(listOf(evening), emptyList(), at("22:30"), zone).isEmpty())
        assertTrue(TrainAlertWatch.due(listOf(early), emptyList(), at("06:30"), zone).isEmpty())
    }

    @Test fun `a train of the followed trip is checked around that trip's own times`() {
        val followed = listOf(FollowedTrainTimes("REG 2153", at("15:00"), at("16:00")))

        assertEquals(at("11:00")..at("16:30"), TrainAlertWatch.window(subscription("2153", "08:30"), today, followed, zone))
        // Another day: the favourite's departure again.
        assertEquals(at("07:00", today.plusDays(1))..at("11:30", today.plusDays(1)),
            TrainAlertWatch.window(subscription("2153", "08:30"), today.plusDays(1), followed, zone))
    }

    @Test fun `the next run is in 15 minutes inside a window, else the next window opening`() {
        val morning = subscription("2153", "08:30")

        assertEquals(at("09:15"), TrainAlertWatch.nextRun(listOf(morning), emptyList(), at("09:00"), zone))
        assertEquals(at("07:00", today.plusDays(1)), TrainAlertWatch.nextRun(listOf(morning), emptyList(), at("12:00"), zone))
        assertEquals(at("07:00"), TrainAlertWatch.nextRun(listOf(morning), emptyList(), at("05:00"), zone))
        assertNull(TrainAlertWatch.nextRun(emptyList(), emptyList(), at("09:00"), zone))
    }

    @Test fun `only notices not seen before and not older than the subscription are new`() {
        val notices = listOf(
            WatchedNotice("vt:1", "Treno in ritardo per guasto", at("08:00")),
            WatchedNotice("vt:2", "Treno limitato a Pavia", at("08:10")),
            WatchedNotice("vt:2", "Treno limitato a Pavia", at("08:10")),
            WatchedNotice("news:9", "Circolazione rallentata", null),
            WatchedNotice("vt:0", "Avviso di ieri, già visto nel dettaglio", subscribed.minusSeconds(60))
        )
        val seen = mapOf("vt:1" to today.toEpochDay())

        val fresh = TrainAlertWatch.newNotices(notices, seen, subscribed)

        assertEquals(listOf("vt:2", "news:9"), fresh.map { it.id })
        val remembered = TrainAlertWatch.remember(seen, notices, today)
        assertTrue(TrainAlertWatch.newNotices(notices, remembered, subscribed).isEmpty())
    }

    @Test fun `seen ids expire after a week and stay bounded`() {
        val old = mapOf("vt:old" to today.minusDays(8).toEpochDay(), "vt:recent" to today.minusDays(2).toEpochDay())
        val many = (0 until 600).map { WatchedNotice("vt:$it", "x", null) }

        assertEquals(setOf("vt:recent"), TrainAlertWatch.remember(old, emptyList(), today).keys)
        assertEquals(TrainAlertWatch.MAX_SEEN, TrainAlertWatch.remember(emptyMap(), many, today).size)
    }

    @Test fun `only favourite or followed trains are watched and only Trenitalia numbers can be subscribed`() {
        val subscriptions = listOf(subscription("2153", "08:30"), subscription("9999", "10:00"))

        assertEquals(listOf("2153"), TrainAlertWatch.active(subscriptions, listOf("REG 2153"), emptyList()).map { it.number })
        assertEquals(listOf("9999"), TrainAlertWatch.active(subscriptions, emptyList(), listOf("9999")).map { it.number })

        val trenitalia = service("2153", RailOperator.TRENITALIA_REGIONALE)
        val italo = service("9911", RailOperator.ITALO)
        val now = at("07:30")
        val created = TrainAlertWatch.subscriptionOf(TrainNoticeContext.from(trenitalia, today), trenitalia.number, "08:30", "REG 2153", now)!!
        assertEquals("2153", created.publicNumber)
        assertEquals(now, created.subscribedAt)
        assertNull(TrainAlertWatch.subscriptionOf(TrainNoticeContext.from(italo, today), italo.number, "09:10", "Italo 9911", now))
    }

    @Test fun `subscriptions and seen ids survive the codec and damaged entries are skipped`() {
        val subscriptions = listOf(subscription("2153", "08:30"), subscription("2399", ""))
        val raw = TrainAlertCodec.encodeSubscriptions(subscriptions)

        assertEquals(subscriptions, TrainAlertCodec.decodeSubscriptions(raw))
        assertEquals(emptyList<TrainAlertSubscription>(), TrainAlertCodec.decodeSubscriptions("{broken"))
        assertEquals(1, TrainAlertCodec.decodeSubscriptions("""[{"number":"1","publicNumber":"1","operator":"NOPE","subscribedAt":1},
            {"number":"2","publicNumber":"2","operator":"ITALO","subscribedAt":1}]""").size)
        assertEquals(mapOf("vt:1" to 5L), TrainAlertCodec.decodeSeen(TrainAlertCodec.encodeSeen(mapOf("vt:1" to 5L))))
    }

    private fun service(number: String, operator: RailOperator) = TrainService(
        id = "gtfs:$number",
        number = number,
        operator = operator,
        trainCategory = TrainCategory.REGIONALE,
        origin = "Milano Centrale",
        destination = "Pavia",
        scheduledDeparture = "08:30",
        expectedDeparture = "08:30",
        platform = null,
        delayMinutes = 0,
        status = ServiceStatus.ON_TIME,
        stops = emptyList()
    )
}
