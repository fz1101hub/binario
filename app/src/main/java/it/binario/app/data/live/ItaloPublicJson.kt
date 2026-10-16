package it.binario.app.data.live

import it.binario.app.data.*
import org.json.JSONObject
import org.json.JSONTokener
import java.text.Normalizer
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class ItaloPublicStation(val code: String, val name: String, val slug: String, val aliases: Set<String> = emptySet())

internal fun italoStationKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")

/** Reads the station list used by the operator's own passenger-facing page. */
internal fun parseItaloStationCatalog(html: String): List<ItaloPublicStation> {
    val stations = mutableListOf<ItaloPublicStation>()
    fun add(code: String, name: String, slug: String = "") {
        if (!code.matches(Regex("[A-Za-z0-9_]{2,12}")) || name.isBlank()) return
        val resolvedSlug = slug.ifBlank {
            Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        }
        stations += ItaloPublicStation(code, name, resolvedSlug)
    }
    fun JSONObject.first(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        optString(key).takeIf { it.isNotBlank() && it != "null" }
    }.orEmpty()
    Regex("[\"']?station(?:List|Coding)[\"']?\\s*[:=]\\s*[\\[{]", RegexOption.IGNORE_CASE)
        .findAll(html).forEach { match ->
            val value = runCatching { JSONTokener(html.substring(match.range.last)).nextValue() }.getOrNull()
            when (value) {
                is org.json.JSONArray -> for (index in 0 until value.length()) {
                    val item = value.optJSONObject(index) ?: continue
                    add(item.first("code", "value", "Codice", "CodiceStazione", "stationCode"),
                        item.first("name", "label", "text", "Descrizione", "NomeStazione", "stationName"), item.first("slug"))
                }
                is JSONObject -> value.keys().forEach { code ->
                    when (val item = value.opt(code)) {
                        is String -> add(code, item)
                        is JSONObject -> add(item.first("code", "CodiceStazione").ifBlank { code },
                            item.first("name", "label", "Descrizione", "NomeStazione"), item.first("slug"))
                    }
                }
            }
        }
    return stations.distinctBy { it.code }
}

internal fun parseItaloPublicBoard(json: String, origin: String, route: SavedRoute, direction: Direction, clock: Clock): List<TrainService> {
    val root = JSONObject(json)
    if (root.optBoolean("IsEmpty", false)) return emptyList()
    val publishedStation = root.optString("DescrizioneLocalita")
    val requestedNames = (listOf(route.origin(direction).name) + route.origin(direction).aliases).map(::italoStationKey).toSet()
    val originNames = requestedNames + ItaloPublicStationFallbacks.all.filter { station ->
        (listOf(station.name) + station.aliases).any { italoStationKey(it) in requestedNames }
    }.flatMap { (listOf(it.name) + it.aliases).map(::italoStationKey) }
    if (publishedStation.isNotBlank() && italoStationKey(publishedStation) !in originNames) return emptyList()
    val observed = parseItaloObservation(root.optString("LastUpdate"), clock)
    val fresh = observed != null && !observed.isBefore(clock.instant().minusSeconds(300)) && !observed.isAfter(clock.instant().plusSeconds(60))
    val rows = root.optJSONArray("ListaTreniPartenza") ?: return emptyList()
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val number = row.optString("Numero").takeIf { it.matches(Regex("\\d{3,5}")) } ?: return@mapNotNull null
        val destination = row.optString("DescrizioneLocalita").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        val scheduled = row.optString("OraPassaggio").takeIf { it.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")) } ?: return@mapNotNull null
        val delay = row.optString("Ritardo").toIntOrNull()
        val info = row.optString("Informazioni") + " " + row.optString("Descrizione")
        val cancelled = info.hasExplicitCancellationStatus()
        val verified = fresh && (delay != null || cancelled)
        val publishedExpected = row.optString("NuovoOrario").takeIf { it.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")) }
        val expected = if (verified) publishedExpected ?: formatTime(timeToMinutes(scheduled) + (delay ?: 0)) else scheduled
        val platform = row.optString("Binario").takeIf { it.isNotBlank() && it !in setOf("null", "-", "N/D") }
        TrainService(
            id = "italo:${route.id}:${direction.name}:$number:$scheduled", number = number,
            operator = RailOperator.ITALO, trainCategory = TrainCategory.AV_ITALO, origin = origin, destination = destination,
            scheduledDeparture = scheduled, expectedDeparture = expected, platform = platform,
            delayMinutes = if (verified) delay ?: 0 else 0, officialDelayMinutes = delay.takeIf { verified },
            status = when { verified && cancelled -> ServiceStatus.CANCELLED; verified && (delay ?: 0) > 0 -> ServiceStatus.DELAYED; else -> ServiceStatus.ON_TIME },
            stops = listOf(TrainStop(origin, scheduled, expected, platform, if (verified) delay ?: 0 else 0, false, operationalStatusVerified = verified && !cancelled),
                TrainStop(destination, "—", "—", null, 0, false, operationalStatusVerified = false)),
            operationalStatusVerified = verified, operationalSourceIds = if (verified) setOf("italo-status") else emptySet(),
            operationalObservedAtEpochMillis = observed?.toEpochMilli()
        )
    }
}

private fun parseItaloObservation(value: String, clock: Clock): Instant? {
    if (value.isBlank()) return clock.instant() // A current board without a timestamp, as with the RFI monitor.
    runCatching { Instant.parse(value) }.getOrNull()?.let { return it }
    Regex("/Date\\((\\d+)(?:[+-]\\d+)?\\)/").matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()?.let { return Instant.ofEpochMilli(it) }
    val zone = RailTime.defaultZone
    runCatching { LocalDateTime.parse(value).atZone(zone).toInstant() }.getOrNull()?.let { return it }
    for (pattern in listOf("dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "yyyy-MM-dd'T'HH:mm:ss")) {
        runCatching { LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern)).atZone(zone).toInstant() }.getOrNull()?.let { return it }
    }
    val time = runCatching { LocalTime.parse(value) }.getOrNull() ?: return null
    var instant = clock.instant().atZone(zone).toLocalDate().atTime(time).atZone(zone).toInstant()
    if (instant.isAfter(clock.instant().plusSeconds(60))) instant = instant.minusSeconds(24 * 3600)
    return instant
}
