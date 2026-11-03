package it.binario.app.widget

import it.binario.app.data.Freshness
import java.util.concurrent.TimeUnit

enum class WidgetLayoutMode(val visibleRows: Int, val showColumnHeader: Boolean, val routeTextSizeSp: Float, val rowTextSizeSp: Float) {
    COMPACT(2, false, 14f, 12f),
    STANDARD(5, true, 16f, 13f),
    EXPANDED(5, true, 18f, 15f);

    companion object {
        fun fromSize(widthDp: Int, heightDp: Int): WidgetLayoutMode = when {
            widthDp >= 380 && heightDp >= 300 -> EXPANDED
            widthDp < 250 || heightDp < 170 -> COMPACT
            else -> STANDARD
        }
    }
}

/**
 * La posizione persa dal widget è un offset di righe, non un indice pagina.
 * Così il ridimensionamento non salta corse quando cambia [visibleRows].
 */
object WidgetRowPagination {
    fun maxOffset(serviceCount: Int, visibleRows: Int): Int {
        if (serviceCount <= 0) return 0
        val rows = visibleRows.coerceAtLeast(1)
        return ((serviceCount - 1) / rows) * rows
    }

    fun normalizedOffset(offset: Int, serviceCount: Int, visibleRows: Int): Int =
        offset.coerceIn(0, maxOffset(serviceCount, visibleRows))

    fun move(offset: Int, deltaPages: Int, serviceCount: Int, visibleRows: Int): Int {
        val rows = visibleRows.coerceAtLeast(1)
        return normalizedOffset(offset + (deltaPages * rows), serviceCount, rows)
    }

    fun pageNumber(offset: Int, visibleRows: Int): Int = (offset / visibleRows.coerceAtLeast(1)) + 1

    fun pageCount(serviceCount: Int, visibleRows: Int): Int =
        (maxOffset(serviceCount, visibleRows) / visibleRows.coerceAtLeast(1)) + 1
}

/** Nessun dato con più di quindici minuti viene presentato come appena aggiornato. */
object WidgetFreshness {
    private val freshForMillis = TimeUnit.MINUTES.toMillis(5)
    private val agingForMillis = TimeUnit.MINUTES.toMillis(15)

    fun fromAge(retrievedAtEpochMillis: Long, nowEpochMillis: Long = System.currentTimeMillis()): Freshness {
        if (retrievedAtEpochMillis <= 0L) return Freshness.UNAVAILABLE
        val age = (nowEpochMillis - retrievedAtEpochMillis).coerceAtLeast(0L)
        return when {
            age < freshForMillis -> Freshness.FRESH
            age < agingForMillis -> Freshness.AGING
            else -> Freshness.STALE
        }
    }
}
