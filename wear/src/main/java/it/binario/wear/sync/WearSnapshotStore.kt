package it.binario.wear.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataMap
import it.binario.wear.complication.BinarioComplicationService
import it.binario.wear.model.WearSnapshot
import it.binario.wear.model.WearSnapshotCodec
import it.binario.wear.tile.BinarioTileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Time source deliberately passed to the runtime so expiry can be verified without wall-clock tests. */
internal fun interface WearSnapshotClock {
    fun nowEpochMillis(): Long
}

internal fun interface WearSnapshotExpiryCancellation {
    fun cancel()
}

/** Schedules exactly one expiry callback for a runtime; implementations must support cancellation. */
internal fun interface WearSnapshotExpiryScheduler {
    fun schedule(expiresAtEpochMillis: Long, action: () -> Unit): WearSnapshotExpiryCancellation
}

internal interface WearSnapshotStorage {
    fun read(): WearSnapshot?
    fun write(snapshot: WearSnapshot)
    fun clear()
}

/**
 * Owns the in-memory StateFlow and its persisted value as one TTL-bound unit. Every surface must
 * read through [current] and UI observes [state], whose scheduled revocation also handles a long
 * lived Compose session. A rejected inbound value never evicts a still-valid cached value.
 */
internal class WearSnapshotRuntime(
    private val storage: WearSnapshotStorage,
    private val clock: WearSnapshotClock,
    private val scheduler: WearSnapshotExpiryScheduler,
    private val onExpired: () -> Unit = {},
) {
    private val lock = Any()
    private val stateFlow = MutableStateFlow<WearSnapshot?>(null)
    private var expiryCancellation: WearSnapshotExpiryCancellation? = null
    private var expiryGeneration = 0L

    init {
        var expiredAtStartup = false
        synchronized(lock) {
            val restored = storage.read()
            if (restored != null && isCurrent(restored)) {
                stateFlow.value = restored
                scheduleExpiryLocked(restored)
            } else {
                // A malformed or expired persisted payload is never recoverable; fail closed.
                if (restored != null) {
                    storage.clear()
                    expiredAtStartup = true
                }
            }
        }
        if (expiredAtStartup) onExpired()
    }

    fun state(): StateFlow<WearSnapshot?> {
        current()
        return stateFlow
    }

    /** Revalidates before returning so Tile and complication cannot render a late cache. */
    fun current(): WearSnapshot? {
        var notifyExpired = false
        val snapshot = synchronized(lock) {
            val candidate = stateFlow.value
            if (candidate != null && !isCurrent(candidate)) {
                invalidateLocked()
                notifyExpired = true
                null
            } else {
                candidate
            }
        }
        if (notifyExpired) onExpired()
        return snapshot
    }

    /** Valid values replace the cache; invalid input is ignored without weakening a valid cache. */
    fun update(snapshot: WearSnapshot) {
        var notifyExpired = false
        synchronized(lock) {
            if (stateFlow.value?.let(::isCurrent) == false) {
                invalidateLocked()
                notifyExpired = true
            }
            if (isCurrent(snapshot)) {
                storage.write(snapshot)
                stateFlow.value = snapshot
                scheduleExpiryLocked(snapshot)
            }
        }
        if (notifyExpired) onExpired()
    }

    fun clear() {
        synchronized(lock) { invalidateLocked() }
    }

    fun nowEpochMillis(): Long = clock.nowEpochMillis()

    private fun isCurrent(snapshot: WearSnapshot): Boolean = clock.nowEpochMillis() < snapshot.expiresAtEpochMillis

    private fun scheduleExpiryLocked(snapshot: WearSnapshot) {
        expiryCancellation?.cancel()
        val generation = ++expiryGeneration
        expiryCancellation = scheduler.schedule(snapshot.expiresAtEpochMillis) {
            var notifyExpired = false
            synchronized(lock) {
                // The generation invalidates a callback replaced by an update, even when the
                // new value compares equal and StateFlow therefore keeps its previous instance.
                if (generation != expiryGeneration) return@schedule
                if (isCurrent(snapshot)) {
                    // A wall-clock correction can make a delayed callback arrive early; try again.
                    scheduleExpiryLocked(snapshot)
                } else {
                    invalidateLocked()
                    notifyExpired = true
                }
            }
            if (notifyExpired) onExpired()
        }
    }

    private fun invalidateLocked() {
        expiryCancellation?.cancel()
        expiryCancellation = null
        expiryGeneration++
        storage.clear()
        stateFlow.value = null
    }
}

private class AndroidWearSnapshotStorage(
    private val context: Context,
    private val clock: WearSnapshotClock,
) : WearSnapshotStorage {
    override fun read(): WearSnapshot? = runCatching {
        val raw = preferences().getString(KEY_SNAPSHOT, null) ?: return null
        val objectValue = JSONObject(raw)
        val payload = objectValue.keys().asSequence().associateWith { key -> objectValue.opt(key) }
        WearSnapshotCodec.decode(payload, clock.nowEpochMillis()).also { snapshot ->
            if (snapshot == null) clear()
        }
    }.getOrNull()

    override fun write(snapshot: WearSnapshot) {
        preferences().edit { putString(KEY_SNAPSHOT, JSONObject(WearSnapshotCodec.encode(snapshot)).toString()) }
    }

    override fun clear() {
        preferences().edit { remove(KEY_SNAPSHOT) }
    }

    private fun preferences() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private companion object {
        const val PREFERENCES = "binario_wear_snapshot_v1"
        const val KEY_SNAPSHOT = "snapshot"
    }
}

private class HandlerWearSnapshotExpiryScheduler(
    private val handler: Handler,
    private val clock: WearSnapshotClock,
) : WearSnapshotExpiryScheduler {
    override fun schedule(expiresAtEpochMillis: Long, action: () -> Unit): WearSnapshotExpiryCancellation {
        val callback = Runnable(action)
        handler.postDelayed(callback, (expiresAtEpochMillis - clock.nowEpochMillis()).coerceAtLeast(0L))
        return WearSnapshotExpiryCancellation { handler.removeCallbacks(callback) }
    }
}

/**
 * Cache-first store. Its only scheduled work belongs to an application-context entry and is
 * cancelled on refresh/clear; no Activity, Tile or complication is captured by the callback.
 */
object WearSnapshotStore {
    private val clock = WearSnapshotClock { System.currentTimeMillis() }
    private val entries = mutableMapOf<String, WearSnapshotRuntime>()

    /** UI observes this flow; it is cleared at TTL expiry even if the session remains open. */
    fun snapshot(context: Context): StateFlow<WearSnapshot?> = entry(context).state()

    /** Tile and complication use the same revalidating path as UI initialization. */
    fun current(context: Context): WearSnapshot? = entry(context).current()

    fun updateFromDataMap(context: Context, dataMap: DataMap) {
        val runtime = entry(context)
        val payload = buildMap<String, Any?> {
            dataMap.keySet().forEach { key -> put(key, dataMap.get(key)) }
        }
        // Decode against the same injected runtime clock that enforces the in-memory TTL.
        WearSnapshotCodec.decode(payload, runtime.nowEpochMillis())?.let(runtime::update)
    }

    fun clear(context: Context) {
        entry(context).clear()
    }

    private fun entry(context: Context): WearSnapshotRuntime {
        val applicationContext = context.applicationContext
        return synchronized(entries) {
            entries.getOrPut(applicationContext.packageName) {
                WearSnapshotRuntime(
                    storage = AndroidWearSnapshotStorage(applicationContext, clock),
                    clock = clock,
                    scheduler = HandlerWearSnapshotExpiryScheduler(Handler(Looper.getMainLooper()), clock),
                    onExpired = { requestSurfaceUpdate(applicationContext) },
                )
            }
        }
    }

    private fun requestSurfaceUpdate(context: Context) {
        TileService.getUpdater(context).requestUpdate(BinarioTileService::class.java)
        ComplicationDataSourceUpdateRequester.create(
            context,
            android.content.ComponentName(context, BinarioComplicationService::class.java),
        ).requestUpdateAll()
    }
}
