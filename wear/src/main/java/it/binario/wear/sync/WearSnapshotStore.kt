package it.binario.wear.sync

import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.wearable.DataMap
import it.binario.wear.model.WearSnapshot
import it.binario.wear.model.WearSnapshotCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Cache-first store. A malformed or unknown Data Layer payload never replaces a valid local snapshot. */
object WearSnapshotStore {
    private const val PREFERENCES = "binario_wear_snapshot_v1"
    private const val KEY_SNAPSHOT = "snapshot"
    private val states = mutableMapOf<String, MutableStateFlow<WearSnapshot?>>()

    fun snapshot(context: Context): StateFlow<WearSnapshot?> = synchronized(states) {
        states.getOrPut(context.applicationContext.packageName) {
            MutableStateFlow(read(context.applicationContext))
        }
    }

    fun updateFromDataMap(context: Context, dataMap: DataMap) {
        val payload = buildMap<String, Any?> {
            dataMap.keySet().forEach { key -> put(key, dataMap.get(key)) }
        }
        WearSnapshotCodec.decode(payload)?.let { snapshot -> update(context.applicationContext, snapshot) }
    }

    private fun update(context: Context, snapshot: WearSnapshot) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(KEY_SNAPSHOT, JSONObject(WearSnapshotCodec.encode(snapshot)).toString())
        }
        synchronized(states) {
            states.getOrPut(context.packageName) { MutableStateFlow(read(context)) }.value = snapshot
        }
    }

    private fun read(context: Context): WearSnapshot? = runCatching {
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_SNAPSHOT, null) ?: return null
        val objectValue = JSONObject(raw)
        val payload = objectValue.keys().asSequence().associateWith { key -> objectValue.opt(key) }
        WearSnapshotCodec.decode(payload)
    }.getOrNull()
}
