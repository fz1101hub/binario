package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I data pack embedded vivono in tre luoghi che devono restare allineati:
 * gli asset ZIP, manifest.json (sha256 e validità) e SHA256SUMS. Il test
 * legge i file reali del modulo così che un refresh parziale fallisca in CI.
 */
class EmbeddedGtfsAssetIntegrityTest {
    private val gtfsDirectory: File = listOf(File("src/main/assets/gtfs"), File("app/src/main/assets/gtfs"))
        .firstOrNull(File::isDirectory)
        ?: error("Directory degli asset GTFS non trovata da ${File(".").absolutePath}")

    private val zipFiles: Map<String, File> = gtfsDirectory
        .listFiles { file -> file.isFile && file.name.endsWith(".zip") }
        .orEmpty()
        .associateBy { it.name }

    private val manifestPacks: List<JSONObject> = JSONObject(File(gtfsDirectory, "manifest.json").readText())
        .getJSONArray("packs")
        .let { packs -> List(packs.length()) { index -> packs.getJSONObject(index) } }

    @Test
    fun `il manifest elenca esattamente gli asset presenti su disco`() {
        val manifestAssets = manifestPacks.map { it.getString("asset").removePrefix("gtfs/") }

        assertEquals(manifestAssets.size, manifestAssets.toSet().size)
        assertEquals(zipFiles.keys, manifestAssets.toSet())
    }

    @Test
    fun `ogni sha256 del manifest corrisponde al file reale`() {
        manifestPacks.forEach { pack ->
            val name = pack.getString("asset").removePrefix("gtfs/")
            val file = requireNotNull(zipFiles[name]) { "Asset $name assente su disco" }
            assertEquals("sha256 non allineato per $name", pack.getString("sha256"), sha256(file))
        }
    }

    @Test
    fun `SHA256SUMS copre tutti gli asset con impronte corrette`() {
        val listed = File(gtfsDirectory, "SHA256SUMS").readLines()
            .filter(String::isNotBlank)
            .associate { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                assertEquals("Riga SHA256SUMS non valida: $line", 2, parts.size)
                parts[1].removePrefix("*") to parts[0]
            }

        assertEquals(zipFiles.keys, listed.keys)
        listed.forEach { (name, hash) ->
            assertEquals("SHA256SUMS non allineato per $name", sha256(requireNotNull(zipFiles[name])), hash)
        }
    }

    @Test
    fun `il manifest e EmbeddedGtfsPacks descrivono gli stessi pack`() {
        val manifestByAsset = manifestPacks.associateBy { it.getString("asset") }

        assertEquals(manifestByAsset.keys, EmbeddedGtfsPacks.all.map(EmbeddedGtfsPack::assetPath).toSet())
        EmbeddedGtfsPacks.all.forEach { pack ->
            val entry = requireNotNull(manifestByAsset[pack.assetPath])
            assertEquals("sourceId non allineato per ${pack.assetPath}", SourceId(entry.getString("sourceId")), pack.sourceId)
            assertEquals(
                "lastServiceDate non allineato per ${pack.assetPath}",
                LocalDate.parse(entry.getString("lastServiceDate")),
                pack.lastServiceDate
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
