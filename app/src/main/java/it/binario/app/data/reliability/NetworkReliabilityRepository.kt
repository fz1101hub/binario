package it.binario.app.data.reliability

import android.content.Context
import it.binario.app.BuildConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The embedded network statistics ([NetworkReliabilityStats]), read once per process, lazily and off the main
 * thread, from the app's own assets: no request leaves the device. Null, and the feature hidden, when the build
 * leaves the statistics out (`-Pbinario.punctualityStats=false`, [BuildConfig.NETWORK_PUNCTUALITY_STATS]) or the
 * file is absent, unreadable or of another schema.
 */
object NetworkReliabilityRepository {
    const val ASSET_PATH = "punctuality/stats_v1.json.gz"

    val enabled: Boolean get() = BuildConfig.NETWORK_PUNCTUALITY_STATS

    private val mutex = Mutex()
    @Volatile private var loaded: Loaded? = null

    private class Loaded(val stats: NetworkReliabilityStats?)

    /** The statistics already read, without reading them. */
    fun cached(): NetworkReliabilityStats? = loaded?.stats

    suspend fun load(context: Context): NetworkReliabilityStats? {
        if (!enabled) return null
        loaded?.let { return it.stats }
        val application = context.applicationContext
        return mutex.withLock {
            loaded?.let { return@withLock it.stats }
            val stats = withContext(Dispatchers.IO) { read(application) }
            loaded = Loaded(stats)
            stats
        }
    }

    private fun read(context: Context): NetworkReliabilityStats? = try {
        context.assets.open(ASSET_PATH).use { NetworkReliabilityParser.parseGzip(it) }
    } catch (_: IOException) {
        null
    }
}
