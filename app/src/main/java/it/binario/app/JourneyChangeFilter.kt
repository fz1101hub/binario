package it.binario.app

enum class JourneyChangeFilter(val label: String, val accessibleLabel: String) {
    ANY("Qualsiasi", "Qualsiasi numero di cambi"),
    DIRECT("Diretto", "Solo viaggi diretti, senza cambi"),
    UP_TO_ONE("Max 1 cambio", "Viaggi con al massimo un cambio"),
    UP_TO_TWO("Max 2 cambi", "Viaggi con al massimo due cambi");

    fun accepts(transferCount: Int): Boolean = when (this) {
        ANY -> true
        DIRECT -> transferCount == 0
        UP_TO_ONE -> transferCount <= 1
        UP_TO_TWO -> transferCount <= 2
    }
}
