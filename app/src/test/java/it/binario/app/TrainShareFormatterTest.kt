package it.binario.app

import it.binario.app.data.RailOperator
import it.binario.app.data.Freshness
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainShareFormatterTest {
    @Test
    fun `shared text uses Italian strings without translating station or provider names`() {
        val content = TrainShareFormatter.create(
            service = service(),
            provider = "Catalogo locale",
            confidence = "Dato dimostrativo",
            freshness = null,
            refreshedAt = 0L,
            isDemonstration = true,
            strings = italianStrings()
        )

        assertEquals("Treno 4132 · Roma Termini → Firenze S. M. Novella", content.title)
        assertTrue(content.text.contains("Roma Tiburtina"))
        assertTrue(content.text.contains("Stato: In ritardo (+5 min)"))
        assertTrue(content.text.contains("Dati: Catalogo dimostrativo locale, non live"))
        assertTrue(content.text.contains("Fonte: Catalogo locale · Dato dimostrativo"))
    }

    @Test
    fun `shared text uses English strings without translating station or provider names`() {
        val content = TrainShareFormatter.create(
            service = service(),
            provider = "Local catalogue",
            confidence = "Demonstration data",
            freshness = Freshness.FRESH,
            refreshedAt = 0L,
            isDemonstration = false,
            strings = englishStrings()
        )

        assertEquals("Train 4132 · Roma Termini → Firenze S. M. Novella", content.title)
        assertTrue(content.text.contains("Expected departure: 09:05 (scheduled 09:00)"))
        assertTrue(content.text.contains("Status: Delayed (+5 min)"))
        assertTrue(content.text.contains("• Roma Tiburtina — expected 09:15, platform 2, +5 min"))
        assertTrue(content.text.contains("Source: Local catalogue · Demonstration data"))
    }

    private fun service() = TrainService(
        id = "test", number = "4132", operator = RailOperator.TRENITALIA_REGIONALE,
        trainCategory = TrainCategory.REGIONALE, origin = "Roma Termini", destination = "Firenze S. M. Novella",
        scheduledDeparture = "09:00", expectedDeparture = "09:05", platform = "6", delayMinutes = 5,
        status = ServiceStatus.DELAYED,
        stops = listOf(TrainStop("Roma Tiburtina", "09:10", "09:15", "2", 5, false))
    )

    private fun italianStrings() = strings(
        locale = Locale.ITALIAN,
        title = "Treno %1\$s · %2\$s → %3\$s",
        detailTitle = "Binario — dettaglio treno",
        expectedDeparture = "Partenza prevista: %1\$s",
        scheduledDeparture = "programmata %1\$s",
        platform = "Binario: %1\$s",
        platformToConfirm = "Binario da confermare",
        status = "Stato: %1\$s",
        stopsTitle = "Fermate",
        stop = "• %1\$s — previsto %2\$s%3\$s%4\$s",
        stopPlatform = ", binario %1\$s",
        data = "Dati: %1\$s",
        source = "Fonte: %1\$s · %2\$s",
        neverUpdated = "Aggiornamento: mai aggiornato",
        demonstrationData = "Catalogo dimostrativo locale, non live",
        scheduledData = "Orario programmato; stato live non disponibile",
        unavailableData = "Dati live non disponibili",
        liveData = "Dati live: %1\$s",
        onTime = "In orario",
        delayed = "In ritardo",
        cancelled = "Soppresso",
        regional = "Regionale",
        fresh = "Dati aggiornati"
    )

    private fun englishStrings() = strings(
        locale = Locale.ENGLISH,
        title = "Train %1\$s · %2\$s → %3\$s",
        detailTitle = "Binario — train details",
        expectedDeparture = "Expected departure: %1\$s",
        scheduledDeparture = "scheduled %1\$s",
        platform = "Platform: %1\$s",
        platformToConfirm = "Platform to be confirmed",
        status = "Status: %1\$s",
        stopsTitle = "Stops",
        stop = "• %1\$s — expected %2\$s%3\$s%4\$s",
        stopPlatform = ", platform %1\$s",
        data = "Data: %1\$s",
        source = "Source: %1\$s · %2\$s",
        neverUpdated = "Update: never updated",
        demonstrationData = "Local demonstration catalogue, not live",
        scheduledData = "Scheduled timetable; live status unavailable",
        unavailableData = "Live data unavailable",
        liveData = "Live data: %1\$s",
        onTime = "On time",
        delayed = "Delayed",
        cancelled = "Cancelled",
        regional = "Regional",
        fresh = "Data up to date"
    )

    private fun strings(
        locale: Locale,
        title: String,
        detailTitle: String,
        expectedDeparture: String,
        scheduledDeparture: String,
        platform: String,
        platformToConfirm: String,
        status: String,
        stopsTitle: String,
        stop: String,
        stopPlatform: String,
        data: String,
        source: String,
        neverUpdated: String,
        demonstrationData: String,
        scheduledData: String,
        unavailableData: String,
        liveData: String,
        onTime: String,
        delayed: String,
        cancelled: String,
        regional: String,
        fresh: String
    ) = TrainShareStrings(
        locale = locale,
        title = title,
        detailTitle = detailTitle,
        operatorCategory = "%1\$s · %2\$s %3\$s",
        route = "%1\$s → %2\$s",
        expectedDeparture = expectedDeparture,
        scheduledDeparture = scheduledDeparture,
        platform = platform,
        platformToConfirm = platformToConfirm,
        status = status,
        stopsTitle = stopsTitle,
        stop = stop,
        stopPlatform = stopPlatform,
        stopDelay = ", +%1\$d min",
        data = data,
        source = source,
        updated = "Updated: %1\$s",
        neverUpdated = neverUpdated,
        demonstrationData = demonstrationData,
        scheduledData = scheduledData,
        unavailableData = unavailableData,
        liveData = liveData,
        statusLabels = mapOf(
            ServiceStatus.ON_TIME to onTime,
            ServiceStatus.DELAYED to delayed,
            ServiceStatus.CANCELLED to cancelled
        ),
        categoryLabels = TrainCategory.entries.associateWith { if (it == TrainCategory.REGIONALE) regional else it.name },
        freshnessLabels = Freshness.entries.associateWith { if (it == Freshness.FRESH) fresh else it.name }
    )
}
