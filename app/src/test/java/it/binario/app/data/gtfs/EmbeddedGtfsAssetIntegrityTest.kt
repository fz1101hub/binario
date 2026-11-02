package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I data pack embedded vivono in tre luoghi che devono restare allineati:
 * gli asset ZIP, manifest.json (sha256 e validità) e SHA256SUMS. Il test
 * legge i file reali del modulo così che un refresh parziale fallisca in CI.
 *
 * Le stesse regole valgono per le tre directory: `gtfs/` (pack ferroviari del primo rilascio), `gtfs-transit/`
 * (ferrovie regionali e reti urbane, ogni build) e `gtfs-personal/` (solo build personali, `src/personal/assets`).
 */
class EmbeddedGtfsAssetIntegrityTest {
    private val moduleRoot: File = listOf(File("."), File("app")).firstOrNull { File(it, "src/main/assets/gtfs").isDirectory }
        ?: error("Directory degli asset GTFS non trovata da ${File(".").absolutePath}")

    private class Directory(val name: String, val root: File) {
        val zipFiles: Map<String, File> = root.listFiles { file -> file.isFile && file.name.endsWith(".zip") }.orEmpty().associateBy { it.name }
        val manifestPacks: List<JSONObject> = JSONObject(File(root, "manifest.json").readText())
            .getJSONArray("packs")
            .let { packs -> List(packs.length()) { index -> packs.getJSONObject(index) } }
    }

    private val directories: List<Directory> = listOf(
        "gtfs" to File(moduleRoot, "src/main/assets/gtfs"),
        "gtfs-transit" to File(moduleRoot, "src/main/assets/gtfs-transit"),
        "gtfs-personal" to File(moduleRoot, "src/personal/assets/gtfs-personal")
    ).filter { (_, root) -> File(root, "manifest.json").isFile }.map { (name, root) -> Directory(name, root) }

    private val gtfsDirectory: Directory get() = directories.single { it.name == "gtfs" }

    @Test
    fun `il manifest elenca esattamente gli asset presenti su disco`() {
        directories.forEach { directory ->
            val manifestAssets = directory.manifestPacks.map { it.getString("asset") }
            assertTrue("asset fuori da ${directory.name}/", manifestAssets.all { it.startsWith(directory.name + "/") })
            val names = manifestAssets.map { it.removePrefix(directory.name + "/") }
            assertEquals(names.size, names.toSet().size)
            assertEquals(directory.zipFiles.keys, names.toSet())
        }
    }

    @Test
    fun `ogni sha256 del manifest corrisponde al file reale`() {
        directories.forEach { directory ->
            directory.manifestPacks.forEach { pack ->
                val name = pack.getString("asset").removePrefix(directory.name + "/")
                val file = requireNotNull(directory.zipFiles[name]) { "Asset $name assente su disco" }
                assertEquals("sha256 non allineato per $name", pack.getString("sha256"), sha256(file))
            }
        }
    }

    @Test
    fun `SHA256SUMS copre tutti gli asset con impronte corrette`() {
        directories.forEach { directory ->
            val listed = File(directory.root, "SHA256SUMS").readLines()
                .filter(String::isNotBlank)
                .associate { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    assertEquals("Riga SHA256SUMS non valida: $line", 2, parts.size)
                    parts[1].removePrefix("*") to parts[0]
                }

            assertEquals(directory.zipFiles.keys, listed.keys)
            listed.forEach { (name, hash) ->
                assertEquals("SHA256SUMS non allineato per $name", sha256(requireNotNull(directory.zipFiles[name])), hash)
            }
        }
    }

    @Test
    fun `il manifest e EmbeddedGtfsPacks descrivono gli stessi pack`() {
        // Every pack of a personal build (the unit tests run the debug variant, which includes them).
        val packs = EmbeddedGtfsPacks.forBuild(personalSources = true)
        val manifestByAsset = directories.flatMap { it.manifestPacks }.associateBy { it.getString("asset") }

        assertEquals(manifestByAsset.keys, packs.map(EmbeddedGtfsPack::assetPath).toSet())
        packs.forEach { pack ->
            val entry = requireNotNull(manifestByAsset[pack.assetPath])
            assertEquals("sourceId non allineato per ${pack.assetPath}", SourceId(entry.getString("sourceId")), pack.sourceId)
            assertEquals(
                "lastServiceDate non allineato per ${pack.assetPath}",
                LocalDate.parse(entry.getString("lastServiceDate")),
                pack.lastServiceDate
            )
            // Personal-build packs live in gtfs-personal/ only; a city pack is "urban" in its manifest.
            assertEquals("personalOnly non allineato per ${pack.assetPath}", pack.assetPath.startsWith("gtfs-personal/"), pack.personalOnly)
            if (!pack.assetPath.startsWith("gtfs/")) {
                assertEquals("kind non allineato per ${pack.assetPath}", if (pack.isUrban) "urban" else "rail", entry.getString("kind"))
                assertEquals(if (pack.personalOnly) "personal" else "all", entry.getString("distribution"))
            }
            if (pack.isUrban) assertTrue("area mancante per ${pack.assetPath}", pack.areas.isNotEmpty())
        }
    }

    @Test
    fun `una build pubblica non contiene i pack personali`() {
        val public = EmbeddedGtfsPacks.forBuild(personalSources = false)
        assertTrue(public.none(EmbeddedGtfsPack::personalOnly))
        assertTrue(public.none { it.assetPath.startsWith("gtfs-personal/") })
        assertEquals(gtfsDirectory.manifestPacks.size + (directories.firstOrNull { it.name == "gtfs-transit" }?.manifestPacks?.size ?: 0), public.size)
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
