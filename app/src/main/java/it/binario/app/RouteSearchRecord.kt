package it.binario.app

import java.time.LocalDateTime

/**
 * The planner search the route results answer, small enough for the saved instance state: the stations by id, the
 * planned departure (null for "Adesso", which follows the current time) and the return of a round trip. After the
 * process is recreated the results repeat this search instead of showing another route or an empty list.
 */
internal data class RouteSearchRecord(
    val originId: String,
    val destinationId: String,
    val departure: LocalDateTime? = null,
    val returnAt: LocalDateTime? = null
) {
    fun encode(): String = listOf(originId, destinationId, departure?.toString().orEmpty(), returnAt?.toString().orEmpty())
        .joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "\u001F"

        fun decode(value: String?): RouteSearchRecord? {
            val parts = value?.split(SEPARATOR)?.takeIf { it.size == 4 } ?: return null
            if (parts[0].isBlank() || parts[1].isBlank()) return null
            fun dateTime(text: String): LocalDateTime? = text.takeIf(String::isNotBlank)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            val departure = dateTime(parts[2])
            if (parts[2].isNotBlank() && departure == null) return null
            return RouteSearchRecord(parts[0], parts[1], departure, dateTime(parts[3]))
        }
    }
}
