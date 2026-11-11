package it.binario.app.ui.common

/**
 * When a row of a train list gives up its side column. The time on the left and the platform on the right are
 * measured before the destination between them: with large text, or with the long «Bin. 7 (era 5)», they leave it
 * a few letters («Per Milano Porta G…» is Garibaldi and Genova). The platform then goes under the destination.
 */
internal object ListRowLayout {
    /** The font scale from which the side column is stacked (the system setting «large» is 1.15, «largest» 1.3). */
    const val LARGE_TEXT_SCALE = 1.3f

    fun stacksSideColumn(fontScale: Float, wideSide: Boolean = false): Boolean = wideSide || fontScale >= LARGE_TEXT_SCALE
}
