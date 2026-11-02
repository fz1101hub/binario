package it.binario.app.ui

import it.binario.app.ui.common.LocalizedDates
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizedDatesTest {
    private val date = LocalDate.of(2026, 11, 2)

    @Test
    fun `cjk languages write the month before the day`() {
        assertEquals("11月2日", date.format(LocalizedDates.dayMonth(Locale.JAPANESE)))
        assertEquals("11月2日", date.format(LocalizedDates.dayMonth(Locale.SIMPLIFIED_CHINESE)))
        assertEquals("11월 2일", date.format(LocalizedDates.dayMonth(Locale.KOREAN)))
    }

    @Test
    fun `other languages keep day then month`() {
        assertEquals("d MMM", LocalizedDates.dayMonthPattern(Locale.ITALIAN))
        assertEquals("EEE d MMM", LocalizedDates.weekdayDayMonthPattern(Locale("ar")))
    }
}
