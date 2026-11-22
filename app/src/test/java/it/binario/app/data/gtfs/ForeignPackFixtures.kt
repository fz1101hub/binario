package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pins files and full-pack archives for the foreign-pack tests (schema 1 of foreign_packs.json, with D2's borderOnly). */
internal object ForeignPackFixtures {
    /** The sample pins of this repository's tests: ÖBB pinned (border subset bundled), CH and SNCF bundled whole. */
    val sampleFile = File("src/test/resources/foreign/foreign_packs_v1.json")

    fun sampleJson(): String = sampleFile.readText()

    val oebb = SourceId("oebb-gtfs")
    val swiss = SourceId("ch-otd-gtfs")
    val sncf = SourceId("sncf-gtfs")

    /** A small archive standing for a full pack: a GTFS table and the manifest naming [sourceId]. */
    fun archive(sourceId: String, extra: String = ""): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("agency.txt"))
            zip.write("agency_id,agency_name,agency_url,agency_timezone\nA,Prova$extra,https://example.test,Europe/Vienna\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("binario_filter_manifest.json"))
            zip.write("{\"sourceId\":\"$sourceId\",\"filterVersion\":2}".toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    /** One pack entry of a pins file; [bytes] null writes an unpinned pack (`android: null`, `borderOnly: false`). */
    fun packJson(
        sourceId: String,
        country: String,
        bytes: ByteArray?,
        served: List<String>,
        zone: List<String>,
        lastServiceDate: LocalDate = LocalDate.of(2026, 12, 12),
        borderOnly: Boolean = bytes != null
    ): String {
        val android = bytes?.let {
            val sha = sha256(it)
            val tag = "gtfs-full-$sourceId-${sha.take(12)}"
            """{"tag":"$tag","asset":"$sourceId.zip","url":"https://github.com/fz1101hub/binario/releases/download/$tag/$sourceId.zip","sizeBytes":${it.size},"sha256":"$sha","contentSha256":null}"""
        } ?: "null"
        fun list(values: List<String>) = values.joinToString(",", "[", "]") { "\"$it\"" }
        return """{"sourceId":"$sourceId","country":"$country","lastServiceDate":"$lastServiceDate","fullServiceUntil":"$lastServiceDate",""" +
            """"servedStationIds":${list(served)},"zoneStationIds":${list(zone)},"borderOnly":$borderOnly,"android":$android,"ios":null}"""
    }

    fun pinsJson(vararg packs: String): String =
        """{"schemaVersion":1,"generatedAt":"2026-11-22","repository":"fz1101hub/binario","borderZone":{"radiusKm":35},"packs":[${packs.joinToString(",")}]}"""

    fun pins(vararg packs: String): ForeignPackPins = ForeignPackPins.parse(pinsJson(*packs))
}
