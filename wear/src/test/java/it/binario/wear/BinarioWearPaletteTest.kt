package it.binario.wear

import java.io.File
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinarioWearPaletteTest {
    @Test
    fun `palette carries the Binario 2_0 dark design tokens`() {
        assertEquals(0xFF9BDD9E, BinarioWearPalette.PRIMARY)
        assertEquals(0xFF205B2A, BinarioWearPalette.PRIMARY_CONTAINER)
        assertEquals(0xFF142018, BinarioWearPalette.SURFACE_LOW)
        assertEquals(0xFFFFC489, BinarioWearPalette.WARNING)
        assertEquals(0xFFFFB4AB, BinarioWearPalette.ERROR)
        // OLED watches: the screen background stays true black.
        assertEquals(0xFF000000, BinarioWearPalette.BACKGROUND)
    }

    @Test
    fun `every text role keeps WCAG AA contrast on black and on the card containers`() {
        val surfaces = listOf(
            BinarioWearPalette.BACKGROUND,
            BinarioWearPalette.SURFACE_LOW,
            BinarioWearPalette.SURFACE,
            BinarioWearPalette.SURFACE_HIGH,
        )
        val textOnSurfaces = listOf(
            BinarioWearPalette.PRIMARY,
            BinarioWearPalette.PRIMARY_DIM,
            BinarioWearPalette.ON_BACKGROUND,
            BinarioWearPalette.ON_SURFACE,
            BinarioWearPalette.ON_SURFACE_VARIANT,
            BinarioWearPalette.ERROR,
            BinarioWearPalette.WARNING,
        )
        for (surface in surfaces) for (text in textOnSurfaces) assertAa(text, surface)

        assertAa(BinarioWearPalette.ON_PRIMARY, BinarioWearPalette.PRIMARY)
        assertAa(BinarioWearPalette.ON_PRIMARY, BinarioWearPalette.PRIMARY_DIM)
        assertAa(BinarioWearPalette.ON_PRIMARY_CONTAINER, BinarioWearPalette.PRIMARY_CONTAINER)
        assertAa(BinarioWearPalette.ON_ERROR, BinarioWearPalette.ERROR)
        assertAa(BinarioWearPalette.ON_ERROR_CONTAINER, BinarioWearPalette.ERROR_CONTAINER)
        // Non-text boundaries (outlined controls) need 3:1 against the background.
        assertTrue(contrast(BinarioWearPalette.OUTLINE, BinarioWearPalette.BACKGROUND) >= 3.0)
    }

    @Test
    fun `screen and Tile take their colours from the palette instead of literals`() {
        val sources = listOf("WearMainActivity.kt", "tile/BinarioTileService.kt", "complication/BinarioComplicationService.kt")
        for (name in sources) {
            val source = File(sourceRoot, name).readText()
            assertFalse("$name must not hard-code ARGB colours", Regex("""0x[Ff]{2}[0-9A-Fa-f]{6}""").containsMatchIn(source))
            assertFalse("$name must not build ad-hoc Compose colours", source.contains("Color(0x"))
        }
        assertTrue(File(sourceRoot, "WearMainActivity.kt").readText().contains("BinarioWearTheme {"))
    }

    private fun assertAa(foreground: Long, background: Long) {
        val ratio = contrast(foreground, background)
        assertTrue(
            "contrast ${"%08X".format(foreground)} on ${"%08X".format(background)} is %.2f".format(ratio),
            ratio >= 4.5,
        )
    }

    private fun contrast(first: Long, second: Long): Double {
        val (light, dark) = listOf(luminance(first), luminance(second)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xFF) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private val sourceRoot: File
        get() = sequenceOf(File("src/main/java/it/binario/wear"), File("wear/src/main/java/it/binario/wear"))
            .firstOrNull(File::isDirectory)
            ?: error("Unable to locate the Wear sources from ${File(".").absolutePath}")
}
