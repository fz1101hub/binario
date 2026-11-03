package it.binario.app.ui.settings

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class CommuterAlertTimeTest {
    private val original = Locale.getDefault()

    @After fun restoreLocale() = Locale.setDefault(original)

    @Test
    fun `the stored alert time keeps ASCII digits in Arabic, Hindi and Burmese`() {
        listOf("ar", "hi-u-nu-deva", "my", "it").forEach { tag ->
            Locale.setDefault(Locale.forLanguageTag(tag))
            assertEquals(tag, "07:05", commuterAlertTime(7, 5))
            assertEquals(tag, "18:30", commuterAlertTime(18, 30))
        }
    }
}
