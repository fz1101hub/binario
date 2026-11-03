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
}
