package it.binario.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateTimeMessageTest {
    @Test
    fun `update time is a resource key with the railway-local clock time, not Italian prose`() {
        // 2030-01-01T07:10Z is 08:10 in Europe/Rome.
        assertEquals(UiMessage(UiMessageKey.UPDATE_TIME_AT, "08:10"), updateTimeMessage(1_893_481_800_000L))
    }

    @Test
    fun `missing refresh time resolves to the never-updated key`() {
        assertEquals(UiMessage(UiMessageKey.UPDATE_TIME_NEVER), updateTimeMessage(0L))
        assertEquals(UiMessage(UiMessageKey.UPDATE_TIME_NEVER), updateTimeMessage(-1L))
    }
}
