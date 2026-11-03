package it.binario.app.widget

import it.binario.app.data.Station
import it.binario.app.data.core.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationWidgetCodecTest {
    @Test
    fun `a configured station keeps every identity field the board request needs`() {
        val station = Station(
            id = "S08409",
            name = "Roma Termini",
            city = "Roma",
            region = "Lazio",
            railwayCode = "S08409",
            trenitaliaLocationId = "S08409",
            aliases = listOf("Roma T.", "Termini, Roma; centro"),
            latitude = 41.9009,
            longitude = 12.5018
        )

        assertEquals(station, StationWidgetCodec.decodeStation(StationWidgetCodec.encodeStation(station)))
    }

    @Test
    fun `optional station fields stay empty instead of becoming text`() {
        val station = Station("manual:borgo", "Borgo San Dalmazzo", "Borgo San Dalmazzo")

        assertEquals(station, StationWidgetCodec.decodeStation(StationWidgetCodec.encodeStation(station)))
    }

    @Test
    fun `unreadable or nameless stations are rejected`() {
        assertNull(StationWidgetCodec.decodeStation("not base64 at all"))
        assertNull(StationWidgetCodec.decodeStation(""))
        assertNull(StationWidgetCodec.decodeStation(StationWidgetCodec.encodeStation(Station("", "Roma", "Roma"))))
    }

    @Test
    fun `departures round trip with their observation and without inventing missing fields`() {
        val departures = listOf(
            StationWidgetDeparture(1_000L, 1_420_000L, "2345", "trenitalia", "Napoli Centrale, via Formia", "3; ovest", RunStatus.DELAYED),
            StationWidgetDeparture(2_000L, null, null, null, "Fiumicino Aeroporto", null, null)
        )

        assertEquals(departures, StationWidgetCodec.decodeDepartures(StationWidgetCodec.encodeDepartures(departures)))
        assertEquals(emptyList<StationWidgetDeparture>(), StationWidgetCodec.decodeDepartures(""))
    }

    @Test
    fun `a damaged record is dropped alone and an unknown status reads as unknown`() {
        val good = StationWidgetDeparture(1_000L, null, "1", null, "Latina", null, RunStatus.ON_TIME)
        val encoded = StationWidgetCodec.encodeDepartures(listOf(good)) + ";garbage,,;" +
            StationWidgetCodec.encodeDepartures(listOf(good.copy(number = "2"))).replace(
                java.util.Base64.getEncoder().encodeToString("ON_TIME".toByteArray()),
                java.util.Base64.getEncoder().encodeToString("TELEPORTED".toByteArray())
            )

        val decoded = StationWidgetCodec.decodeDepartures(encoded)

        assertEquals(listOf("1", "2"), decoded.map { it.number })
        assertEquals(RunStatus.UNKNOWN, decoded.last().status)
    }

    @Test
    fun `provider names survive commas`() {
        val names = listOf("RFI, tabellone", "Italo")

        assertEquals(names, StationWidgetCodec.decodeNames(StationWidgetCodec.encodeNames(names)))
        assertEquals(emptyList<String>(), StationWidgetCodec.decodeNames(""))
    }
}
