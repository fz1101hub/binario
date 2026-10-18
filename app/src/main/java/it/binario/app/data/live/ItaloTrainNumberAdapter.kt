package it.binario.app.data.live

import it.binario.app.data.*
import it.binario.app.data.core.SourceId
import it.binario.app.data.planned.BoundedSingleFlightCache
import it.binario.app.data.provider.*
import it.binario.app.data.repository.*
import java.io.IOException
import java.time.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Public website train lookup; no account, station scanning, private APIs or third-party proxy. */
class ItaloTrainNumberAdapter(
    private val fetch: suspend (String) -> String = ::downloadItaloPublic,
    private val clock: Clock = Clock.system(RailTime.defaultZone)
) : TrainNumberSearchProvider {
    override val descriptor = ProviderDescriptor(SourceId("italo-status"), "Italo in Viaggio",
        setOf(ProviderCapability.TRAIN_RUN_REALTIME), CoverageDescriptor(countryCodes = setOf("IT"), description = "Ricerca pubblica Italo per numero"),
        AccessMode.PUBLIC_BEST_EFFORT, RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = Duration.ofSeconds(30)), 1)
    private val cache = BoundedSingleFlightCache<Result<String>>(32, 30_000, clock::millis)
    private val zone = RailTime.defaultZone

    override suspend fun searchByNumber(query: TrainNumberQuery): ProviderResult<List<TrainNumberMatch>> = withContext(Dispatchers.IO) {
        val fetched = clock.instant()
        val meta = ProviderResponseMeta(descriptor.id, fetched, expiresAt = fetched.plusSeconds(30))
        val number = query.numeric?.takeIf { it.matches(Regex("[0-9]{3,5}")) }
            ?: return@withContext ProviderResult.Success(emptyList(), meta)
        if (query.date != fetched.atZone(zone).toLocalDate()) return@withContext ProviderResult.Success(emptyList(), meta)
        try {
            val body = cache.getOrLoad(number, expireFromLoadStart = true) {
                try { Result.success(fetch("${ItaloPublicStationAdapter.BASE_URL}/api/RicercaTrenoService?TrainNumber=$number")) }
                catch (error: CancellationException) { throw error }
                catch (error: IOException) { Result.failure(error) }
            }.value.getOrThrow()
            val root = JSONObject(body)
            if (root.optBoolean("IsEmpty", false)) return@withContext ProviderResult.Success(emptyList(), meta)
            val service = parse(root, query, fetched) ?: return@withContext ProviderResult.Failure(
                ProviderError(ProviderErrorKind.INVALID_DATA, "La corsa Italo non corrisponde al numero o alla data richiesti."))
            ProviderResult.Success(listOf(TrainNumberMatch.create(service, descriptor.displayName, DataConfidence.OPERATOR_PRIMARY,
                fetched.toEpochMilli(), fetched.plusSeconds(30).toEpochMilli())), meta)
        } catch (error: CancellationException) { throw error }
        catch (_: IOException) { ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "Ricerca Italo non raggiungibile.")) }
        catch (_: org.json.JSONException) { ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "Risposta Italo non leggibile.")) }
    }

    internal fun parse(root: JSONObject, query: TrainNumberQuery, fetched: Instant): TrainService? {
        val run = root.optJSONObject("TrainSchedule") ?: return null
        val number = run.text("TrainNumber") ?: run.text("RfiTrainNumber") ?: return null
        if (!query.matches(number)) return null
        val departureDate = dateTime(run.text("DepartureDate"), query.date)?.toLocalDate() ?: query.date
        val calls = buildList {
            run.optJSONObject("StazionePartenza")?.let { add(it to true) }
            for ((key, reached) in listOf("StazioniFerme" to true, "StazioniNonFerme" to false)) {
                val rows = run.optJSONArray(key) ?: continue
                for (i in 0 until minOf(rows.length(), 500)) rows.optJSONObject(i)?.let { add(it to reached) }
            }
        }.distinctBy { (row, _) -> listOf(row.text("LocationCode"), row.text("StationNumber"), row.text("EstimatedDepartureTime"), row.text("EstimatedArrivalTime")) }
            .sortedBy { (row, _) -> row.text("StationNumber")?.toIntOrNull() ?: Int.MAX_VALUE }
        if (calls.size < 2) return null
        var day = departureDate
        var previous: ZonedDateTime? = null
        val stops = calls.mapIndexedNotNull { index, (row, reached) ->
            val name = row.text("LocationDescription") ?: return@mapIndexedNotNull null
            val scheduledText = row.text(if (index == 0) "EstimatedDepartureTime" else "EstimatedArrivalTime")
                ?: row.text("EstimatedDepartureTime") ?: row.text("EstimatedArrivalTime") ?: return@mapIndexedNotNull null
            var scheduled = dateTime(scheduledText, day) ?: return@mapIndexedNotNull null
            if (previous != null && scheduled.isBefore(previous!!.minusHours(12))) scheduled = scheduled.plusDays(1)
            day = scheduled.toLocalDate()
            previous = scheduled
            val actualText = row.text(if (index == 0) "ActualDepartureTime" else "ActualArrivalDateTime") ?: row.text("ActualArrivalTime")
            val actual = if (reached) dateTime(actualText, day)?.takeIf {
                // Ignore sentinel/default and future actuals; midnight itself is a valid railway time.
                it.year >= 2000 && !it.isAfter(fetched.atZone(zone).plusMinutes(1)) && !it.isBefore(scheduled.minusMinutes(15))
            } else null
            TimedStop(name, scheduled, actual, row.text("ActualArrivalPlatform"))
        }
        if (stops.size < 2 || stops.first().scheduled.toLocalDate() > query.date || stops.last().scheduled.toLocalDate() < query.date) return null
        val observed = root.text("LastUpdate")?.let { parseItaloObservation(it, clock) }?.takeIf {
            !it.isBefore(fetched.minusSeconds(300)) && !it.isAfter(fetched.plusSeconds(60))
        }
        val disruption = run.optJSONObject("Distruption")
        val delay = disruption?.text("DelayAmount")?.toIntOrNull()
        val state = disruption?.text("RunningState").orEmpty()
        val cancelled = state.hasExplicitCancellationStatus()
        val verified = observed != null && (cancelled || (delay != null && (stops.any { it.actual != null } || state.uppercase() in setOf("IN CORSA", "IN VIAGGIO", "ARRIVATO"))))
        val trainStops = stops.map { stop ->
            val expected = stop.actual ?: if (verified && !cancelled) stop.scheduled.plusMinutes((delay ?: 0).toLong()) else stop.scheduled
            TrainStop(stop.name, hhmm(stop.scheduled), hhmm(expected), stop.platform,
                Duration.between(stop.scheduled, expected).toMinutes().toInt(), stop.actual != null,
                operationalStatusVerified = stop.actual != null || (verified && !cancelled))
        }
        return TrainService(
            id = "italo:$number:${stops.first().scheduled.toInstant().toEpochMilli()}", number = number,
            operator = RailOperator.ITALO, trainCategory = TrainCategory.AV_ITALO,
            origin = stops.first().name, destination = stops.last().name, scheduledDeparture = trainStops.first().scheduled,
            expectedDeparture = trainStops.first().expected, platform = trainStops.first().platform,
            delayMinutes = if (verified) delay ?: 0 else 0, officialDelayMinutes = delay.takeIf { verified },
            status = if (verified && cancelled) ServiceStatus.CANCELLED else if (verified && (delay ?: 0) > 0) ServiceStatus.DELAYED else ServiceStatus.ON_TIME,
            stops = trainStops, operationalStatusVerified = verified, scheduledDepartureDateTime = stops.first().scheduled.toLocalDateTime(),
            operationalSourceIds = if (verified) setOf(descriptor.id.value) else emptySet(), operationalObservedAtEpochMillis = observed?.toEpochMilli().takeIf { verified })
    }

    private data class TimedStop(val name: String, val scheduled: ZonedDateTime, val actual: ZonedDateTime?, val platform: String?)
    private fun hhmm(time: ZonedDateTime) = time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    private fun dateTime(value: String?, date: LocalDate): ZonedDateTime? {
        if (value == null) return null
        Regex("/Date\\((\\d+)(?:[+-]\\d+)?\\)/").matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
        runCatching { Instant.parse(value).atZone(zone) }.getOrNull()?.let { return it }
        runCatching { LocalDateTime.parse(value).atZone(zone) }.getOrNull()?.let { return it }
        runCatching { LocalDate.parse(value).atStartOfDay(zone) }.getOrNull()?.let { return it }
        return runCatching { date.atTime(LocalTime.parse(value)).atZone(zone) }.getOrNull()
    }
    private fun JSONObject.text(key: String): String? = opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" }
}
