package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Opens a pack by its asset path for every reader of the embedded timetables: the installed full pack of a foreign
 * country when there is one in [snapshot], otherwise the asset bundled in the APK. Any other asset (the station
 * catalogue) is opened from the APK as before. A full pack is hashed again the first time this process opens it
 * ([ForeignPackStore.verifyOnce]); a mismatch is an IOException, which the readers handle as an unreadable pack.
 */
class GtfsPackOpener(
    private val openAsset: (String) -> InputStream,
    snapshot: ForeignPackSnapshot,
    packs: List<EmbeddedGtfsPack>,
    private val verify: (SourceId, File) -> Unit = { _, _ -> }
) : (String) -> InputStream {
    private val installed: Map<String, Pair<SourceId, File>> = packs
        .mapNotNull { pack -> snapshot.installedFiles[pack.sourceId]?.let { pack.assetPath to (pack.sourceId to it) } }
        .toMap()

    /** The asset paths read from an installed full pack. */
    val installedPaths: Set<String> get() = installed.keys

    override fun invoke(assetPath: String): InputStream {
        val (sourceId, file) = installed[assetPath] ?: return openAsset(assetPath)
        verify(sourceId, file)
        return FileInputStream(file)
    }
}
