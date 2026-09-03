package it.binario.wear.model

/** Immutable cache received from the phone; the watch never fetches railway data itself. */
data class WearSnapshot(
    val routeLabel: String,
    val provider: String,
    val freshness: String,
    val confidence: String,
    val retrievedAtEpochMillis: Long,
    val notice: String,
    val services: List<WearService>
) {
    val dataStatusLabel: String
        get() = when {
            confidence == "STATIC_ONLY" -> "Orario programmato"
            freshness == "UNAVAILABLE" -> "Dati live non disponibili"
            freshness == "STALE" -> "Ultimo snapshot locale"
            freshness == "AGING" -> "Dati in aggiornamento"
            freshness == "FRESH" -> "Stato ricevuto dal telefono"
            else -> "Stato non disponibile"
        }

    val complicationLabel: String
        get() = services.firstOrNull()?.let { service ->
            listOfNotNull(service.expectedDeparture, service.number).joinToString(" · ").take(32)
        } ?: dataStatusLabel.take(32)
}

data class WearService(
    val number: String,
    val operator: String,
    val category: String,
    val destination: String,
    val expectedDeparture: String?,
    val status: String,
    val platform: String?
) {
    val accessibleLabel: String
        get() = listOfNotNull(
            expectedDeparture?.let { "Partenza prevista $it" },
            "Treno $number",
            destination.takeIf { it.isNotBlank() },
            operator.takeIf { it.isNotBlank() },
            category.takeIf { it.isNotBlank() },
            platform?.let { "Binario $it" },
            status.takeIf { it.isNotBlank() }
        ).joinToString(". ")
}

/** Fail-closed decoder shared by Data Layer persistence and unit tests. */
object WearSnapshotCodec {
    private const val VERSION = 1L
    private const val MAX_SERVICES = 2

    fun decode(payload: Map<String, Any?>): WearSnapshot? {
        if (payload.long("version") != VERSION) return null
        val routeLabel = payload.text("routeLabel", 96) ?: return null
        val provider = payload.text("provider", 96) ?: return null
        val freshness = payload.text("freshness", 24) ?: return null
        val confidence = payload.text("confidence", 32) ?: return null
        val retrievedAt = payload.long("retrievedAt") ?: return null
        if (retrievedAt < 0L) return null
        val serviceCount = (payload.long("serviceCount") ?: 0L).coerceIn(0L, MAX_SERVICES.toLong()).toInt()
        val services = (0 until serviceCount).mapNotNull { index ->
            val number = payload.text("service${index}Number", 24) ?: return@mapNotNull null
            val destination = payload.text("service${index}Destination", 96) ?: return@mapNotNull null
            WearService(
                number = number,
                operator = payload.text("service${index}Operator", 64).orEmpty(),
                category = payload.text("service${index}Category", 64).orEmpty(),
                destination = destination,
                expectedDeparture = payload.text("service${index}Expected", 16),
                status = payload.text("service${index}Status", 64).orEmpty(),
                platform = payload.text("service${index}Platform", 16)
            )
        }
        return WearSnapshot(routeLabel, provider, freshness, confidence, retrievedAt, payload.text("notice", 180).orEmpty(), services)
    }

    fun encode(snapshot: WearSnapshot): Map<String, Any?> = buildMap {
        put("version", VERSION)
        put("routeLabel", snapshot.routeLabel)
        put("provider", snapshot.provider)
        put("freshness", snapshot.freshness)
        put("confidence", snapshot.confidence)
        put("retrievedAt", snapshot.retrievedAtEpochMillis)
        put("notice", snapshot.notice)
        put("serviceCount", snapshot.services.size.coerceAtMost(MAX_SERVICES))
        snapshot.services.take(MAX_SERVICES).forEachIndexed { index, service ->
            put("service${index}Number", service.number)
            put("service${index}Operator", service.operator)
            put("service${index}Category", service.category)
            put("service${index}Destination", service.destination)
            service.expectedDeparture?.let { put("service${index}Expected", it) }
            put("service${index}Status", service.status)
            service.platform?.let { put("service${index}Platform", it) }
        }
    }

    private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

    private fun Map<String, Any?>.text(key: String, limit: Int): String? = (this[key] as? String)
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(limit)
        ?.takeIf { it.isNotBlank() }
}
