package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONException
import org.json.JSONObject

/** What Settings says of a downloadable foreign pack. */
enum class ForeignPackStatus {
    /** Only the border zone is on the phone (bundled). */
    BORDER_ONLY,
    /** The full pack of the current pin is installed. */
    INSTALLED,
    /** A full pack of an older version was installed and removed at the update: it can be downloaded again. */
    UPDATE_AVAILABLE,
    /** The pin's timetable has ended: this version no longer offers the download. */
    EXPIRED
}

/** The installed full packs at one [generation]: the readers of that generation all see the same files. */
class ForeignPackSnapshot(
    val generation: Long,
    val pins: ForeignPackPins,
    val installedFiles: Map<SourceId, File>
) {
    fun isInstalled(sourceId: SourceId): Boolean = sourceId in installedFiles

    /** [base] as the readers must see it: installed packs with the pin's dates, the others as their border subset. */
    fun packs(base: List<EmbeddedGtfsPack>): List<EmbeddedGtfsPack> = EmbeddedGtfsPacks.forStore(base, pins, installedFiles.keys)

    companion object {
        /** Nothing installed and no pins: every pack read as bundled. */
        val NONE = ForeignPackSnapshot(0L, ForeignPackPins.EMPTY, emptyMap())
    }
}

/**
 * The full foreign packs downloaded on request, in a directory the system never backs up (`noBackupFilesDir/gtfs-full/`):
 * `<id>.zip`, its sidecar `<id>.pin.json` (written last, so a ZIP without one was never completed) and, while a
 * download runs, `.<id>.part`. A file counts as installed only for the pin of this version (same SHA-256 and size) and
 * after its bytes were hashed again once in this process. [cleanUp], at startup, removes stray parts and the files of a
 * pin that changed with an app update, leaving a marker so that Settings shows «Aggiornamento disponibile»: nothing is
 * downloaded again without a tap. Every install or delete bumps [generation], and the readers are rebuilt.
 */
class ForeignPackStore(
    private val directory: File,
    val pins: ForeignPackPins,
    private val hash: (File) -> String = ::sha256Hex
) {
    private val mutableGeneration = MutableStateFlow(0L)
    val generation: StateFlow<Long> = mutableGeneration.asStateFlow()

    /** One download at a time. */
    val downloadMutex = Mutex()

    /** `<id>:<sha256>` of the files hashed again in this process. */
    private val verified = HashSet<String>()

    fun zipFile(sourceId: SourceId): File = File(directory, "${sourceId.value}.zip")
    fun partFile(sourceId: SourceId): File = File(directory, ".${sourceId.value}.part")
    private fun sidecarFile(sourceId: SourceId): File = File(directory, "${sourceId.value}$SIDECAR_SUFFIX")
    private fun sidecarTemp(sourceId: SourceId): File = File(directory, ".${sourceId.value}$SIDECAR_SUFFIX.tmp")
    private fun updateMarker(sourceId: SourceId): File = File(directory, "${sourceId.value}$UPDATE_SUFFIX")

    /** Creates the directory if needed and returns it (for the downloader's part file). */
    fun ensureDirectory(): File {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Cartella degli orari non disponibile")
        return directory
    }

    /**
     * Startup cleaning: stray part and temporary files, files of a pack no longer pinned, a ZIP without sidecar (an
     * install that never completed), a sidecar without ZIP, and the files of a pin that changed (they leave the
     * «Aggiornamento disponibile» marker). True when a full pack (or its sidecar) was removed.
     */
    @Synchronized
    fun cleanUp(): Boolean {
        val files = directory.listFiles() ?: return false
        var removed = false
        val ids = LinkedHashSet<String>()
        for (file in files) {
            val name = file.name
            when {
                name.startsWith(".") -> file.delete()
                name.endsWith(".zip") -> ids += name.removeSuffix(".zip")
                name.endsWith(SIDECAR_SUFFIX) -> ids += name.removeSuffix(SIDECAR_SUFFIX)
                name.endsWith(UPDATE_SUFFIX) -> ids += name.removeSuffix(UPDATE_SUFFIX)
            }
        }
        for (id in ids) {
            val sourceId = SourceId(id)
            val pin = pins.entry(sourceId)?.takeIf(ForeignPackEntry::downloadable)?.android
            val zip = zipFile(sourceId)
            val sidecar = sidecarFile(sourceId)
            if (pin == null) {
                removed = zip.delete() or sidecar.delete() or removed
                updateMarker(sourceId).delete()
                continue
            }
            val recorded = readSidecar(sidecar)
            when {
                recorded == null || !zip.isFile -> removed = zip.delete() or sidecar.delete() or removed
                recorded.sha256 != pin.sha256 -> {
                    // The app was updated with a new pin: the old full pack is not used next to the new subsets.
                    removed = zip.delete() or sidecar.delete() or removed
                    writeMarker(sourceId)
                }
                zip.length() != pin.sizeBytes || recorded.sizeBytes != pin.sizeBytes -> removed = zip.delete() or sidecar.delete() or removed
                else -> updateMarker(sourceId).delete()
            }
        }
        return removed
    }

    /** The installed full pack of [sourceId] for the current pin, or null. Cheap: sidecar and size only. */
    @Synchronized
    fun installed(sourceId: SourceId): File? {
        val pin = pins.entry(sourceId)?.takeIf(ForeignPackEntry::downloadable)?.android ?: return null
        val zip = zipFile(sourceId)
        if (!zip.isFile || zip.length() != pin.sizeBytes) return null
        val recorded = readSidecar(sidecarFile(sourceId)) ?: return null
        return zip.takeIf { recorded.sha256 == pin.sha256 && recorded.sizeBytes == pin.sizeBytes }
    }

    @Synchronized
    fun snapshot(): ForeignPackSnapshot = ForeignPackSnapshot(
        generation.value,
        pins,
        pins.downloadable.mapNotNull { entry -> installed(entry.sourceId)?.let { entry.sourceId to it } }.toMap()
    )

    /**
     * Hashes [file] (the installed pack of [sourceId]) once in this process. A mismatch deletes the pack, bumps the
     * generation and throws: the readers then treat the pack as unreadable until they are rebuilt on the subset.
     */
    fun verifyOnce(sourceId: SourceId, file: File) {
        val pin = synchronized(this) {
            pins.entry(sourceId)?.android ?: throw IOException("Pack non previsto")
        }
        val key = "${sourceId.value}:${pin.sha256}"
        if (synchronized(this) { key in verified }) return
        val digest = hash(file)
        synchronized(this) {
            if (digest == pin.sha256 && file.length() == pin.sizeBytes) {
                verified += key
                return
            }
            if (zipFile(sourceId) == file) removeFiles(sourceId)
        }
        bump()
        throw IOException("Il pack scaricato non corrisponde più all'impronta attesa")
    }

    /**
     * Installs the verified download [part] (its SHA-256 and size already equal the pin): moved atomically into place,
     * checked as a GTFS archive of [sourceId] with the bounded readers, and only then given its sidecar.
     */
    fun install(sourceId: SourceId, part: File, limits: GtfsCompactIndexLimits = GtfsCompactIndexLimits()) {
        val pin = pins.entry(sourceId)?.takeIf(ForeignPackEntry::downloadable)?.android
            ?: throw IOException("Pack non scaricabile in questa versione")
        synchronized(this) {
            sidecarFile(sourceId).delete()
            val zip = zipFile(sourceId)
            Files.move(part.toPath(), zip.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            try {
                ForeignPackArchiveCheck.require(zip, sourceId, limits)
            } catch (error: IOException) {
                zip.delete()
                throw error
            }
            val temp = sidecarTemp(sourceId)
            FileOutputStream(temp).use { output ->
                output.write(Sidecar(sourceId.value, pin.tag, pin.sha256, pin.sizeBytes).toJson().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temp.toPath(), sidecarFile(sourceId).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            updateMarker(sourceId).delete()
            verified += "${sourceId.value}:${pin.sha256}"
        }
        bump()
    }

    /** Deletes the full pack of [sourceId] (Settings › Elimina); effective at once: the readers are rebuilt. */
    fun delete(sourceId: SourceId) {
        synchronized(this) {
            removeFiles(sourceId)
            updateMarker(sourceId).delete()
            partFile(sourceId).delete()
        }
        bump()
    }

    /** Settings: the state of the downloadable pack [entry] on [today]. */
    @Synchronized
    fun status(entry: ForeignPackEntry, today: LocalDate): ForeignPackStatus = when {
        installed(entry.sourceId) != null -> ForeignPackStatus.INSTALLED
        entry.expiredOn(today) -> ForeignPackStatus.EXPIRED
        updateMarker(entry.sourceId).isFile -> ForeignPackStatus.UPDATE_AVAILABLE
        else -> ForeignPackStatus.BORDER_ONLY
    }

    private fun removeFiles(sourceId: SourceId) {
        sidecarFile(sourceId).delete()
        zipFile(sourceId).delete()
        verified.removeAll { it.startsWith("${sourceId.value}:") }
    }

    private fun bump() {
        mutableGeneration.value = mutableGeneration.value + 1
    }

    private fun writeMarker(sourceId: SourceId) {
        try {
            updateMarker(sourceId).writeText("")
        } catch (_: IOException) {
            // Only Settings' wording depends on it.
        }
    }

    private fun readSidecar(file: File): Sidecar? {
        if (!file.isFile || file.length() > MAX_SIDECAR_BYTES) return null
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            Sidecar(json.getString("sourceId"), json.getString("tag"), json.getString("sha256"), json.getLong("sizeBytes"))
        } catch (_: JSONException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private data class Sidecar(val sourceId: String, val tag: String, val sha256: String, val sizeBytes: Long) {
        fun toJson(): String = JSONObject()
            .put("sourceId", sourceId)
            .put("tag", tag)
            .put("sha256", sha256)
            .put("sizeBytes", sizeBytes)
            .toString()
    }

    companion object {
        private const val SIDECAR_SUFFIX = ".pin.json"
        private const val UPDATE_SUFFIX = ".update"
        private const val MAX_SIDECAR_BYTES = 16L * 1024L

        fun sha256Hex(file: File): String = FileInputStream(file).use(::sha256Hex)

        fun sha256Hex(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().toHex()
        }

        internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }
}

/**
 * Reads a downloaded pack once with bounded limits (entry count, names, uncompressed size, CRC through ZipInputStream)
 * and requires its `binario_filter_manifest.json` to name [SourceId]: a valid archive of another pack is refused.
 */
internal object ForeignPackArchiveCheck {
    private const val MANIFEST = "binario_filter_manifest.json"
    private const val MAX_MANIFEST_BYTES = 256 * 1024

    fun require(file: File, sourceId: SourceId, limits: GtfsCompactIndexLimits = GtfsCompactIndexLimits()) {
        var manifestSource: String? = null
        var entries = 0
        var total = 0L
        try {
            ZipInputStream(FileInputStream(file)).use { zip ->
                val buffer = ByteArray(16 * 1024)
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries += 1
                        if (entries > limits.maxEntries || entry.name.contains("..")) throw IOException("Archivio non valido")
                        val isManifest = entry.name == MANIFEST
                        val manifest = if (isManifest) java.io.ByteArrayOutputStream() else null
                        var entryBytes = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            total += read
                            if (entryBytes > limits.maxEntryBytes || total > limits.maxTotalUncompressedBytes) throw IOException("Archivio troppo grande")
                            if (manifest != null) {
                                if (manifest.size() + read > MAX_MANIFEST_BYTES) throw IOException("Manifest troppo grande")
                                manifest.write(buffer, 0, read)
                            }
                        }
                        if (manifest != null) {
                            manifestSource = try {
                                JSONObject(manifest.toString(Charsets.UTF_8.name())).optString("sourceId")
                            } catch (_: JSONException) {
                                throw IOException("Manifest non valido")
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (_: ZipException) {
            throw IOException("Archivio non valido")
        } catch (_: IllegalArgumentException) {
            throw IOException("Archivio non valido")
        }
        if (manifestSource != sourceId.value) throw IOException("Il pack scaricato non è quello richiesto")
    }
}
