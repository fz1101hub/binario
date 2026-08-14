package it.binario.app.widget

enum class WidgetLayoutMode(val visibleRows: Int, val showColumnHeader: Boolean, val routeTextSizeSp: Float, val rowTextSizeSp: Float) {
    COMPACT(3, false, 14f, 12f),
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
