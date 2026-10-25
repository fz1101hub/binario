package it.binario.app.data.live

import it.binario.app.data.RailTime
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.TrainNotice
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.TrainNoticeProvider
import it.binario.app.data.provider.TrainNoticeRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-train notices ("smart caring") published by ViaggiaTreno for a commercial train number. Best effort, in the
 * same family as the ViaggiaTreno realtime adapter. The HTTPS endpoint redirects to the same resource over HTTP on
 * `www.viaggiatreno.it`, the single cleartext domain the app permits; nothing else is followed.
 *
 * The answer lists notes of many past days: only notes whose validity covers the requested service day, for the
 * same train number and, when the app knows it, the same origin station code, are returned, latest first.
 */
class ViaggiaTrenoTrainNoticesAdapter internal constructor(
    private val fetcher: PublicSourceFetcher,
    private val clock: Clock,
    retryDelayMillis: Long
) : TrainNoticeProvider {
    constructor(clock: Clock = Clock.systemUTC()) : this(
        HttpPublicSourceFetcher(PublicSourceRequestPolicy(maxBytes = MAX_BYTES, allowViaggiaTrenoCleartextRedirect = true)), clock, 750
    )

    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "ViaggiaTreno — avvisi per treno",
        capabilities = setOf(ProviderCapability.SERVICE_ALERTS),
        coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Avvisi Trenitalia pubblicati per numero treno"),
        accessMode = AccessMode.PUBLIC_BEST_EFFORT,
        ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = TTL),
        schemaVersion = 1
    )

    private val cache = ResilientSourceCache<List<RawNote>>(TTL, Duration.ofMinutes(30), clock, maxEntries = 32, retryDelayMillis = retryDelayMillis)

    override suspend fun getTrainNotices(request: TrainNoticeRequest): ProviderResult<List<TrainNotice>> =
        withContext(Dispatchers.Default) {
            val number = request.publicNumber.trim().trimStart('0').takeIf { it.matches(NUMBER) }
                ?: return@withContext ProviderResult.Failure(ProviderError(ProviderErrorKind.INVALID_REQUEST, "Numero treno non valido per ViaggiaTreno."))
            val origin = request.originCode?.trim()?.uppercase()?.takeIf { it.matches(ORIGIN) }
            val url = "$BASE_URL?commercialTrainNumber=$number"
            cache.get(url) { parseNotes(fetcher.get(url)) }
                .toProviderResult(SOURCE_ID, UNAVAILABLE, UNREADABLE) { notes, fetchedAt ->
                    select(notes, number, origin, request.serviceDate, fetchedAt)
                }
        }

    internal fun parseNotes(body: String): List<RawNote> {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed == "null") return emptyList()
        if (!trimmed.startsWith("[")) throw PublicSourceFormatException("Elenco avvisi atteso")
        val array = JSONArray(trimmed)
        return (0 until minOf(array.length(), MAX_NOTES)).mapNotNull { index -> array.optJSONObject(index)?.let(::parseNote) }
    }

    private fun parseNote(item: JSONObject): RawNote? {
        val id = item.text("id") ?: return null
        val text = item.text("infoNote")?.take(MAX_TEXT) ?: return null
        val start = item.millis("startValidity") ?: return null
        val end = item.millis("endValidity") ?: start
        val trains = item.optJSONArray("trains") ?: return null
        val references = (0 until minOf(trains.length(), 20)).mapNotNull { index ->
            val train = trains.optJSONObject(index) ?: return@mapNotNull null
            val number = train.text("commercialTrainNumber")?.trimStart('0') ?: return@mapNotNull null
            TrainReference(number, train.text("originCode")?.uppercase())
        }
        if (references.isEmpty()) return null
        return RawNote(
            id = id,
            text = text,
            englishText = item.text("infoNoteEn")?.take(MAX_TEXT),
            validFrom = Instant.ofEpochMilli(start).atZone(RailTime.defaultZone).toLocalDate(),
            validUntil = Instant.ofEpochMilli(maxOf(start, end)).atZone(RailTime.defaultZone).toLocalDate(),
            insertedAt = item.millis("insertTimestamp")?.let(Instant::ofEpochMilli),
            trains = references
        )
    }

    internal fun select(notes: List<RawNote>, number: String, origin: String?, serviceDate: LocalDate, fetchedAt: Instant): List<TrainNotice> =
        notes.asSequence()
            .filter { serviceDate in it.validFrom..it.validUntil }
            .filter { note -> note.trains.any { it.number == number && (origin == null || it.originCode == origin) } }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<RawNote> { it.insertedAt ?: Instant.EPOCH }.thenByDescending { it.id })
            .map { note ->
                TrainNotice(
                    id = "${SOURCE_ID.value}:${note.id}",
                    text = note.text,
                    englishText = note.englishText,
                    validFrom = note.validFrom,
                    validUntil = note.validUntil,
                    publishedAt = note.insertedAt,
                    source = SourceRef(
                        sourceId = SOURCE_ID, nativeId = note.id, observedAt = note.insertedAt, fetchedAt = fetchedAt,
                        expiresAt = fetchedAt.plus(TTL), confidence = Confidence.OPERATOR_PRIMARY,
                        traceId = "${SOURCE_ID.value}:$number:${note.id}"
                    )
                )
            }
            .toList()

    private fun JSONObject.text(key: String): String? =
        opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.millis(key: String): Long? = opt(key)?.takeIf { it != JSONObject.NULL }?.let {
        when (it) {
            is Number -> it.toLong()
            else -> it.toString().trim().toLongOrNull()
        }
    }?.takeIf { it > 0 }

    internal data class TrainReference(val number: String, val originCode: String?)

    internal data class RawNote(
        val id: String,
        val text: String,
        val englishText: String?,
        val validFrom: LocalDate,
        val validUntil: LocalDate,
        val insertedAt: Instant?,
        val trains: List<TrainReference>
    )

    companion object {
        val SOURCE_ID = SourceId("viaggiatreno-train-notices")
        internal const val BASE_URL = "https://www.viaggiatreno.it/infomobilitamobile/resteasy/news/smartcaring"
        private val TTL: Duration = Duration.ofMinutes(5)
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_NOTES = 500
        private const val MAX_TEXT = 1_000
        private val NUMBER = Regex("[1-9][0-9]{0,5}")
        private val ORIGIN = Regex("S[0-9]{5}")
        private const val UNAVAILABLE = "Gli avvisi ViaggiaTreno non sono raggiungibili."
        private const val UNREADABLE = "Gli avvisi ViaggiaTreno non sono leggibili."
    }
}
