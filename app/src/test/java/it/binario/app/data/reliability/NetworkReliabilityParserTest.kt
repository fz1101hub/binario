package it.binario.app.data.reliability

import it.binario.app.data.reliability.ReliabilityFixtures.FR_9558
import it.binario.app.data.reliability.ReliabilityFixtures.REG_2041
import it.binario.app.data.reliability.ReliabilityFixtures.document
import it.binario.app.data.reliability.ReliabilityFixtures.gzip
import it.binario.app.data.reliability.ReliabilityFixtures.parse
import it.binario.app.data.reliability.ReliabilityFixtures.stop
import it.binario.app.data.reliability.ReliabilityFixtures.train
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReliabilityParserTest {
    @Test fun `a valid file gives the window, the buckets and every train with its stops`() {
        val stats = requireNotNull(parse(document(FR_9558, REG_2041)))
        assertEquals(Instant.parse("2026-11-10T04:00:00Z"), stats.generatedAt)
        assertEquals(LocalDate.of(2026, 10, 11), stats.windowStart)
        assertEquals(LocalDate.of(2026, 11, 9), stats.windowEnd)
        assertEquals(30, stats.windowDays)
        assertEquals(ReliabilityFixtures.BUCKETS, stats.buckets)
        assertEquals(2, stats.trains.size)
        val train = stats.trainsNumbered("9558").single()
        assertEquals("S08409", train.originCode)
        assertEquals("FR", train.category)
        assertEquals(31, train.daysObserved)
        assertEquals(1, train.daysCancelled)
        assertEquals(3, train.stopCount)
        assertEquals("S01700", train.stationCode(1))
        assertEquals(30, train.samples(1))
        assertEquals(4.6, train.meanDelayMinutes(1), 1e-9)
        assertEquals(22, train.cumulative(1, 2))
        assertEquals(0, train.suppressedDays(1))
        // "FR 9558" and "09558" are the same number.
        assertEquals(listOf(train), stats.trainsNumbered("FR 9558"))
        assertEquals(listOf(train), stats.trainsNumbered("09558"))
    }

    @Test fun `an absent or empty asset hides the feature`() {
        assertNull(NetworkReliabilityParser.parseGzip(ByteArrayInputStream(ByteArray(0))))
        assertNull(NetworkReliabilityParser.parseGzip(ByteArrayInputStream("not gzip".toByteArray())))
    }

    @Test fun `a corrupt file hides the feature`() {
        val valid = gzip(document(FR_9558))
        // Truncated gzip, malformed JSON, trailing data, trailing commas, a document that is not an object.
        assertNull(NetworkReliabilityParser.parseGzip(ByteArrayInputStream(valid.copyOf(valid.size / 2))))
        assertNull(parse("{\"schema\":\"${NetworkReliabilityParser.SCHEMA}\","))
        assertNull(parse(document(FR_9558) + " {}"))
        assertNull(parse(document(FR_9558).replace("\"trains\":[", "\"trains\":[,")))
        assertNull(parse("[1, 2, 3]"))
        assertNull(parse(document(FR_9558).replace("0.8", "0.8.1")))
    }

    @Test fun `an oversized file hides the feature`() {
        val text = document(FR_9558, REG_2041)
        assertNotNull(parse(text))
        assertNull(parse(text, NetworkReliabilityParser.Limits(maxCompressedBytes = 64)))
        assertNull(parse(text, NetworkReliabilityParser.Limits(maxJsonBytes = text.length - 1L)))
        assertNull(parse(text, NetworkReliabilityParser.Limits(maxTrains = 1)))
        // A small gzip of a huge document (a "gzip bomb") stops at the decompressed limit.
        val bomb = gzip(" ".repeat(4 * 1024 * 1024) + text)
        assertTrue(bomb.size < 64 * 1024)
        assertNull(NetworkReliabilityParser.parseGzip(ByteArrayInputStream(bomb), NetworkReliabilityParser.Limits(maxJsonBytes = 1024 * 1024)))
    }

    @Test fun `another or a missing schema hides the feature`() {
        assertNull(parse(document(FR_9558, schema = "binario-punctuality-v0")))
        assertNull(parse(document(FR_9558, schema = "binario-punctuality-v2")))
        assertNull(parse(document(FR_9558).replace("\"schema\":\"${NetworkReliabilityParser.SCHEMA}\",", "")))
        assertNull(parse(document(FR_9558).replace("\"${NetworkReliabilityParser.SCHEMA}\"", "1")))
    }

    @Test fun `buckets that do not increase, or no window, hide the feature`() {
        assertNull(parse(document(FR_9558, buckets = listOf(0, 5, 2, 10, 15, 20, 30, 60))))
        assertNull(parse(document(FR_9558, buckets = emptyList())))
        assertNull(parse(document(FR_9558, buckets = listOf(-1, 2, 5, 10, 15, 20, 30, 60))))
        assertNull(parse(document(FR_9558, window = "\"windowDays\":30").replace(",\"generatedAt\":\"2026-11-10T04:00:00Z\"", "").replace("\"generatedAt\":\"2026-11-10T04:00:00Z\",", "")))
        assertNull(parse(document(FR_9558, window = "\"windowStart\":\"2026-10-11\",\"windowEnd\":\"2026-11-09\",\"windowDays\":0")))
    }

    @Test fun `the window length follows its dates when the file does not state it, and the end its generation`() {
        val derived = requireNotNull(parse(document(FR_9558, window = "\"windowStart\":\"2026-10-11\",\"windowEnd\":\"2026-11-09\"")))
        assertEquals(30, derived.windowDays)
        val fromGeneration = requireNotNull(parse(document(FR_9558, window = "\"windowDays\":30")))
        assertEquals(LocalDate.of(2026, 11, 10), fromGeneration.windowEnd)
    }

    @Test fun `a train whose numbers do not add up is left out and the others stay`() {
        val decreasing = train("1", "S00001", "REG", 10, 0, stop("S00001", 10, 1.0, listOf(5, 4, 6, 7, 8, 9, 10, 10)))
        val aboveSamples = train("2", "S00001", "REG", 10, 0, stop("S00001", 10, 1.0, listOf(5, 6, 7, 8, 9, 10, 11, 11)))
        val shortCounts = train("3", "S00001", "REG", 10, 0, stop("S00001", 10, 1.0, listOf(5, 6, 7)))
        val moreCancelledThanDays = train("4", "S00001", "REG", 10, 11, stop("S00001", 10, 1.0, listOf(5, 6, 7, 8, 9, 10, 10, 10)))
        val noNumber = train("", "S00001", "REG", 10, 0, stop("S00001", 10, 1.0, listOf(5, 6, 7, 8, 9, 10, 10, 10)))
        val stopNotArray = "{\"n\":\"5\",\"o\":\"S00001\",\"c\":\"REG\",\"d\":10,\"x\":0,\"s\":[{\"code\":\"S00001\"}]}"
        val textCount = "{\"n\":\"6\",\"o\":\"S00001\",\"c\":\"REG\",\"d\":\"10\",\"x\":0,\"s\":[${stop("S00001", 10, 1.0, listOf(5, 6, 7, 8, 9, 10, 10, 10))}]}"
        val absurdMean = train("7", "S00001", "REG", 10, 0, stop("S00001", 10, 99999.0, listOf(5, 6, 7, 8, 9, 10, 10, 10)))
        val stats = requireNotNull(parse(document(decreasing, aboveSamples, shortCounts, moreCancelledThanDays, noNumber, stopNotArray, textCount, absurdMean, FR_9558)))
        assertEquals(listOf("9558"), stats.trains.map { it.number })
    }

    @Test fun `a train cancelled on every day observed is listed without stops, any other train needs stops`() {
        val ranWithoutStops = train("2157", "S01700", "REG", 12, 3)
        val stats = requireNotNull(parse(document(ReliabilityFixtures.REG_2155_CANCELLED, ranWithoutStops, FR_9558)))
        assertEquals(listOf("2155", "9558"), stats.trains.map { it.number })
        val cancelled = stats.trainsNumbered("2155").single()
        assertEquals(0, cancelled.stopCount)
        assertEquals(12, cancelled.daysCancelled)
    }

    @Test fun `fields a later pipeline adds are ignored`() {
        val extended = document(
            "{\"n\":\"9558\",\"o\":\"S08409\",\"c\":\"fr\",\"d\":31,\"x\":1,\"note\":{\"a\":[1,2,{\"b\":null}]},\"s\":[" +
                "[\"S08409\",30,0.8,[20,24,27,29,30,30,30,30],0,{\"p90\":12}],[\"S00219\",30,6.2,[8,12,20,25,27,28,29,30],0,true]]}"
        ).replace("\"source\":", "\"extra\":[true,false,null,-1.5e3,\"\\u00e8\\n\"],\"source\":")
        val train = requireNotNull(parse(extended)).trainsNumbered("9558").single()
        assertEquals("FR", train.category)
        assertEquals(2, train.stopCount)
        assertEquals(20, train.cumulative(1, 2))
    }

    @Test fun `the pull reader reads escapes and nested values and rejects malformed input`() {
        val reader = JsonPullReader(StringReader("{\"a\\u0041\":\"x\\\"y\\\\z\\/\",\"skip\":{\"n\":[1,[2,{}],\"]\"]},\"n\":-12.5e1}"))
        reader.beginObject()
        assertEquals("aA", reader.nextName())
        assertEquals("x\"y\\z/", reader.nextString())
        assertEquals("skip", reader.nextName())
        reader.skipValue()
        assertEquals("n", reader.nextName())
        assertEquals(-125.0, reader.nextDouble(), 0.0)
        reader.endObject()
        assertEquals(JsonPullReader.Token.END_DOCUMENT, reader.peek())
        for (bad in listOf("{\"a\":01}", "{\"a\" 1}", "{\"a\":tru}", "[1 2]", "{\"a\":\"\u0001\"}", "[[[[[[[[[[[[[[[[[[[[1]]]]]]]]]]]]]]]]]]]")) {
            val failed = runCatching {
                val json = JsonPullReader(StringReader(bad))
                json.skipValue()
                json.peek()
            }.exceptionOrNull()
            assertTrue("$bad must fail", failed is JsonFormatException)
        }
    }
}
