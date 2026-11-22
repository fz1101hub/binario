package it.binario.app

import java.io.File
import java.io.InputStream

/**
 * The APK assets for JVM tests: `src/main/assets` and, as in a personal build (the debug variant the unit tests run),
 * `src/personal/assets` (the `gtfs-personal/` packs). Tests that hand [open] to a GTFS reader see every pack of
 * [it.binario.app.data.gtfs.EmbeddedGtfsPacks.all] whatever directory it lives in.
 */
object TestAssets {
    private val roots: List<File> = listOf("src/main/assets", "src/personal/assets", "app/src/main/assets", "app/src/personal/assets").map(::File)

    fun file(path: String): File = roots.map { File(it, path) }.firstOrNull(File::isFile) ?: File(roots.first(), path)

    fun open(path: String): InputStream = file(path).inputStream()

    /**
     * The full Android ZIP of a foreign pack bundled as its border subset (`data/gtfs-foreign/android/<id>.zip`, the bytes
     * the app bundled before the subsets and downloads on request), else the asset. For the real-pack tests whose
     * expected trains run beyond the border zone: they read the timetable a user with the full pack installed has.
     */
    fun fullPackFile(path: String): File {
        val id = path.removePrefix("gtfs/").removeSuffix(".zip").takeIf { path.startsWith("gtfs/") && path.endsWith(".zip") && '/' !in it }
        return id?.let { name -> fullPackRoots.map { File(it, "$name.zip") }.firstOrNull(File::isFile) } ?: file(path)
    }

    fun openFull(path: String): InputStream = fullPackFile(path).inputStream()

    private val fullPackRoots = listOf("../data/gtfs-foreign/android", "data/gtfs-foreign/android").map(::File)
}
