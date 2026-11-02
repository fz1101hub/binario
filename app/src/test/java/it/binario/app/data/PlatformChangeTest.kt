package it.binario.app.data

import it.binario.app.TestStringCatalogue
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformChangeTest {
    private fun strings(locale: String?, long: Boolean = false): PlatformLabelStrings {
        val catalogue = TestStringCatalogue.of(locale)
        return PlatformLabelStrings(
            locale = locale?.let(Locale::forLanguageTag) ?: Locale.ITALIAN,
            short = catalogue.getValue(if (long) "platform_label" else "platform_short"),
            changedShort = catalogue.getValue(if (long) "platform_changed_label" else "platform_changed_short"),
            spoken = catalogue.getValue("platform_label"),
            changedSpoken = catalogue.getValue("platform_changed_accessible"),
            unavailable = catalogue.getValue("platform_unavailable")
        )
    }

    @Test fun `a change needs both platforms from the source and a real difference`() {
        assertEquals(PlatformChange("7", "5"), PlatformChange.of("7", "5"))
        assertNull(PlatformChange.of("7", null))
        assertNull(PlatformChange.of(null, "5"))
        assertNull(PlatformChange.of("  ", "5"))
        assertNull(PlatformChange.of("7", " "))
        assertNull(PlatformChange.of("5", "5"))
        assertNull(PlatformChange.of("05", "5"))
        assertNull(PlatformChange.of("7 Ovest", "7  OVEST"))
        assertEquals(PlatformChange("7 Ovest", "7 Est"), PlatformChange.of(" 7 Ovest ", "7 Est"))
    }

    @Test fun `rows show the actual platform with the scheduled one in Italian`() {
        assertEquals("Bin. 7 (era 5)", PlatformLabels.short("7", "5", strings(null)))
        assertEquals("Bin. 7", PlatformLabels.short("7", "7", strings(null)))
        assertEquals("Bin. 7", PlatformLabels.short("7", null, strings(null)))
        assertNull(PlatformLabels.short(null, "5", strings(null)))
        assertEquals("Binario 7 (era 5)", PlatformLabels.short("7", "5", strings(null, long = true)))
    }

    @Test fun `screen readers hear the change in words, never only a colour`() {
        assertEquals("Binario cambiato: 7, previsto 5", PlatformLabels.spoken("7", "5", strings(null)))
        assertEquals("Binario 7", PlatformLabels.spoken("7", "07", strings(null)))
        assertEquals("Binario non disponibile", PlatformLabels.spoken(null, "5", strings(null)))
        assertEquals("Platform changed: 7, scheduled 5", PlatformLabels.spoken("7", "5", strings("en")))
        assertEquals("Plat. 7 (was 5)", PlatformLabels.short("7", "5", strings("en")))
        assertEquals("Gleiswechsel: 7, geplant 5", PlatformLabels.spoken("7", "5", strings("de")))
    }
}
