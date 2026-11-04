package it.binario.app.data.reliability

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Small synthetic statistics files in the `stats_v1.json.gz` format, written by the tests themselves: the real file
 * comes from the CI pipeline and is never committed with the tests.
 */
object ReliabilityFixtures {
    val BUCKETS = listOf(0, 2, 5, 10, 15, 20, 30, 60)

    fun stop(code: String, samples: Int, mean: Double, cumulative: List<Int>, suppressed: Int = 0): String =
        "[\"$code\",$samples,$mean,${cumulative.joinToString(",", "[", "]")},$suppressed]"

    /** A stop of the 4 Nov 2026 extension: with the largest delay and the scheduled minute of the day (JSON null allowed). */
    fun stop7(code: String, samples: Int, mean: Double, cumulative: List<Int>, maxDelay: Int, scheduledMinute: Int?, suppressed: Int = 0): String =
        "[\"$code\",$samples,$mean,${cumulative.joinToString(",", "[", "]")},$suppressed,$maxDelay,${scheduledMinute ?: "null"}]"

    fun train(number: String, origin: String, category: String, days: Int, cancelled: Int, vararg stops: String): String =
        "{\"n\":\"$number\",\"o\":\"$origin\",\"c\":\"$category\",\"d\":$days,\"x\":$cancelled,\"s\":[${stops.joinToString(",")}]}"

    fun document(
        vararg trains: String,
        schema: String = NetworkReliabilityParser.SCHEMA,
        buckets: List<Int> = BUCKETS,
        window: String = "\"windowStart\":\"2026-10-11\",\"windowEnd\":\"2026-11-09\",\"windowDays\":30"
    ): String =
        "{\"schema\":\"$schema\",\"generatedAt\":\"2026-11-10T04:00:00Z\",$window," +
            "\"source\":\"ViaggiaTreno, elaborazione Binario (non ufficiale)\"," +
            "\"buckets\":${buckets.joinToString(",", "[", "]")},\"trains\":[${trains.joinToString(",")}]}"

    fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        GZIPOutputStream(bytes).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

    fun parse(text: String, limits: NetworkReliabilityParser.Limits = NetworkReliabilityParser.Limits()): NetworkReliabilityStats? =
        NetworkReliabilityParser.parseGzip(ByteArrayInputStream(gzip(text)), limits)

    fun stats(vararg trains: String): NetworkReliabilityStats = requireNotNull(parse(document(*trains))) { "fixture must parse" }

    /** FR 9558 Roma Termini → Milano Centrale → Torino Porta Nuova: 31 days, 1 cancelled, 30 samples per stop. */
    val FR_9558 = train(
        "9558", "S08409", "FR", 31, 1,
        stop("S08409", 30, 0.8, listOf(20, 24, 27, 29, 30, 30, 30, 30)),
        stop("S01700", 30, 4.6, listOf(9, 14, 22, 26, 28, 29, 30, 30)),
        stop("S00219", 30, 6.2, listOf(8, 12, 20, 25, 27, 28, 29, 30))
    )

    /** REG 2153 in Lombardy (Milano Centrale → Milano Rogoredo → Pavia). */
    val REG_2153_LOMBARDIA = train(
        "2153", "S01700", "REG", 28, 0,
        stop("S01700", 28, 1.0, listOf(20, 25, 27, 28, 28, 28, 28, 28)),
        stop("S01820", 28, 2.5, listOf(12, 18, 24, 26, 27, 28, 28, 28)),
        stop("S01860", 28, 4.0, listOf(8, 14, 20, 24, 26, 27, 28, 28))
    )

    /** Another REG 2153, in Sicily (Palermo Centrale → Termini Imerese): the same number, another train. */
    val REG_2153_SICILIA = train(
        "2153", "S12001", "REG", 30, 2,
        stop("S12001", 28, 0.5, listOf(26, 27, 28, 28, 28, 28, 28, 28)),
        stop("S12002", 28, 9.0, listOf(5, 8, 12, 16, 21, 25, 27, 28))
    )

    /** REG 2155 from Milano Centrale, cancelled on each of the 12 days observed: listed without stops. */
    val REG_2155_CANCELLED = train("2155", "S01700", "REG", 12, 12)

    /** REG 2041 Milano Centrale → Torino Porta Nuova, measured at 15 minutes. */
    val REG_2041 = train(
        "2041", "S01700", "REG", 30, 0,
        stop("S01700", 30, 0.4, listOf(27, 29, 30, 30, 30, 30, 30, 30)),
        stop("S00219", 30, 3.0, listOf(15, 19, 24, 27, 29, 30, 30, 30))
    )
}
