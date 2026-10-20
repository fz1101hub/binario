package it.binario.app.data.provider

import it.binario.app.BuildConfig

import it.binario.app.data.core.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class ProviderRegistryTest {
    private val ratePolicy = RatePolicy(
        minimumRequestInterval = Duration.ofSeconds(1),
        defaultTimeToLive = Duration.ofMinutes(1)
    )

    @Test
    fun `restituisce solo provider attivi e ordinati per capability`() {
        val staticProvider = policy("static", enabled = true, priority = 20, capability = ProviderCapability.TRAIN_RUN_STATIC)
        val realtimeProvider = policy("realtime", enabled = true, priority = 5, capability = ProviderCapability.TRAIN_RUN_STATIC)
        val disabledProvider = policy("disabled", enabled = false, priority = 1, capability = ProviderCapability.TRAIN_RUN_STATIC)

        val registry = ProviderRegistry(listOf(staticProvider, realtimeProvider, disabledProvider))

        assertEquals(
            listOf(SourceId("realtime"), SourceId("static")),
            registry.activeFor(ProviderCapability.TRAIN_RUN_STATIC).map { it.descriptor.id }
        )
        assertTrue(registry.isEnabled(SourceId("realtime")))
        assertFalse(registry.isEnabled(SourceId("disabled")))
    }

    @Test
    fun `il registry predefinito include le fonti catalogate senza abilitarle`() {
        val registry = KnownProviders.defaultRegistry()

        assertTrue(registry.policy(SourceId("arst-gtfs")) != null)
        assertTrue(registry.policy(SourceId("sncf-gtfs-rt-trip-updates")) != null)
        assertTrue(registry.policy(SourceId("viaggiatreno")) != null)
        assertTrue(registry.isEnabled(SourceId("arst-gtfs")))
        assertEquals(BuildConfig.PERSONAL_REALTIME_SOURCES, registry.isEnabled(SourceId("viaggiatreno")))
        assertEquals(BuildConfig.PERSONAL_REALTIME_SOURCES, registry.isEnabled(SourceId("rfi-station-board")))
        assertEquals(BuildConfig.PERSONAL_REALTIME_SOURCES, registry.isEnabled(SourceId("italo-status")))
        assertTrue(registry.isEnabled(SourceId("rfi-programmed")))
        assertTrue(registry.isEnabled(SourceId("rfi-infomobility-rss")))
        assertTrue(registry.isEnabled(SourceId("alto-adige-sta-realtime")))
        assertTrue(registry.isEnabled(SourceId("sncf-gtfs")))
        assertTrue(registry.isEnabled(SourceId("sncf-gtfs-rt")))
        assertTrue(registry.isEnabled(SourceId("ch-otd-gtfs")))
        assertTrue(registry.isEnabled(SourceId("oebb-gtfs")))
        assertTrue(registry.isEnabled(SourceId("alto-adige-sta-gtfs")))
        assertTrue(registry.isEnabled(SourceId("hzpp-gtfs")))
        assertTrue(registry.policy(SourceId("amt-genova-casella-gtfs")) != null)
        assertTrue(registry.policy(SourceId("cotral-ferro-gtfs")) != null)
        assertFalse(registry.isEnabled(SourceId("amt-genova-casella-gtfs")))
        assertFalse(registry.isEnabled(SourceId("cotral-ferro-gtfs")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rifiuta source id duplicati`() {
        ProviderRegistry(
            listOf(
                policy("duplicate", enabled = true, priority = 1, capability = ProviderCapability.STATION_CATALOG),
                policy("duplicate", enabled = true, priority = 2, capability = ProviderCapability.TRAIN_RUN_STATIC)
            )
        )
    }

    private fun policy(
        id: String,
        enabled: Boolean,
        priority: Int,
        capability: ProviderCapability
    ): ProviderPolicy = ProviderPolicy(
        descriptor = ProviderDescriptor(
            id = SourceId(id),
            displayName = id,
            capabilities = setOf(capability),
            coverage = CoverageDescriptor(description = "fixture"),
            accessMode = AccessMode.OPEN_DIRECT,
            ratePolicy = ratePolicy,
            schemaVersion = 1
        ),
        enabled = enabled,
        priorityByCapability = mapOf(capability to priority)
    )
}
