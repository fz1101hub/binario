package it.binario.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Routes ("Roma → Torino") and time ranges ("17:40 → 22:05") composed in code from Italian station names and digits.
 *
 * In a right-to-left interface (Arabic) the Unicode bidirectional algorithm lays a bare time range out backwards
 * ("22:05 → 17:40": digits have no direction of their own, so the paragraph takes the interface's), and a Latin
 * route would start a left-to-right paragraph aligned to the wrong edge. Inside a left-to-right isolate
 * (U+2066 … U+2069) the unit keeps its reading order with the arrow pointing forward, while the surrounding paragraph
 * keeps the interface direction. Left-to-right interfaces get the text unchanged.
 */
internal object BidiText {
    const val LEFT_TO_RIGHT_ISOLATE = '⁦'
    const val POP_DIRECTIONAL_ISOLATE = '⁩'

    fun ltrUnit(text: String, rightToLeft: Boolean): String =
        if (!rightToLeft || text.isEmpty()) text else "$LEFT_TO_RIGHT_ISOLATE$text$POP_DIRECTIONAL_ISOLATE"
}

/** [text] as one left-to-right unit when the current layout is right to left (see [BidiText]). */
@Composable
@ReadOnlyComposable
internal fun ltrUnit(text: String): String = BidiText.ltrUnit(text, LocalLayoutDirection.current == LayoutDirection.Rtl)
