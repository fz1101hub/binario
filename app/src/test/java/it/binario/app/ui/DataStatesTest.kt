package it.binario.app.ui

import it.binario.app.R
import it.binario.app.data.DataConfidence
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.ui.common.DataState
import it.binario.app.ui.common.ProviderLabels
import org.junit.Assert.assertEquals
import org.junit.Test

class DataStatesTest {
    private fun service(verified: Boolean) = TrainService(
        "id-$verified", "9558", RailOperator.TRENITALIA_AV, TrainCategory.FRECCE, "Roma Termini", "Milano Centrale",
        "17:40", "17:40", null, 0, ServiceStatus.ON_TIME, emptyList(), operationalStatusVerified = verified
    )

    @Test
    fun `an offline timetable is scheduled even while it is aging, never live`() {
        assertEquals(DataState.SCHEDULED, DataState.of(Freshness.AGING, DataConfidence.STATIC_ONLY, listOf(service(false)), false))
        // A live source whose runs carry no verified status is a timetable too.
        assertEquals(DataState.SCHEDULED, DataState.of(Freshness.FRESH, DataConfidence.OPERATOR_PRIMARY, listOf(service(false)), false))
    }

    @Test
    fun `live needs at least one verified run`() {
        assertEquals(DataState.LIVE, DataState.of(Freshness.FRESH, DataConfidence.STATIC_ONLY, listOf(service(false), service(true)), false))
        assertEquals(DataState.LIVE, DataState.of(Freshness.FRESH, DataConfidence.OPERATOR_PRIMARY, emptyList(), false))
        assertEquals(DataState.SCHEDULED, DataState.of(Freshness.FRESH, DataConfidence.STATIC_ONLY, emptyList(), false))
    }

    @Test
    fun `cache, expiry, demonstration and missing data have their own states`() {
        assertEquals(DataState.SAVED_COPY, DataState.of(Freshness.FRESH, DataConfidence.CACHED, listOf(service(true)), false))
        assertEquals(DataState.SAVED_COPY, DataState.of(Freshness.STALE, DataConfidence.OPERATOR_PRIMARY, listOf(service(true)), false))
        assertEquals(DataState.DEMONSTRATION, DataState.of(Freshness.FRESH, DataConfidence.DEMONSTRATION, listOf(service(false)), false))
        assertEquals(DataState.UNAVAILABLE, DataState.of(null, null, emptyList(), false))
        assertEquals(DataState.UNAVAILABLE, DataState.of(Freshness.UNAVAILABLE, DataConfidence.OPEN_DATA, emptyList(), false))
    }

    @Test
    fun `each card follows its own verification inside a live or scheduled list`() {
        assertEquals(DataState.LIVE, DataState.ofService(service(true), DataState.LIVE))
        assertEquals(DataState.SCHEDULED, DataState.ofService(service(false), DataState.LIVE))
        assertEquals(DataState.SAVED_COPY, DataState.ofService(service(false), DataState.SAVED_COPY))
    }

    @Test
    fun `provider descriptors are translated while operator names stay as published`() {
        val translate = { resource: Int ->
            when (resource) {
                R.string.provider_descriptor_scheduled -> "scheduled timetable"
                R.string.provider_descriptor_rfi_programmed -> "Planned timetable"
                R.string.provider_descriptor_rail_source -> "rail source"
                R.string.provider_descriptor_public_board -> "public board"
                else -> "?"
            }
        }
        assertEquals("Trenitalia · scheduled timetable", ProviderLabels.localize("Trenitalia · orario programmato", translate))
        assertEquals(
            "Trenitalia · scheduled timetable + Italo · scheduled timetable + rail source",
            ProviderLabels.localize("Trenitalia · orario programmato + Italo · orario programmato + fonte ferroviaria", translate)
        )
        assertEquals("RFI — Planned timetable", ProviderLabels.localize("RFI — Quadro orario programmato", translate))
        assertEquals("Italo in Viaggio — public board", ProviderLabels.localize("Italo in Viaggio — tabellone pubblico", translate))
        assertEquals("Trenitalia — ViaggiaTreno live", ProviderLabels.localize("Trenitalia — ViaggiaTreno live", translate))
        assertEquals("Regione Toscana / Trenitalia", ProviderLabels.localize("Regione Toscana / Trenitalia", translate))
    }
}
