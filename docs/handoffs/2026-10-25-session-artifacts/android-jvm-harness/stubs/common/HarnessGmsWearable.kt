// Harness compile stubs for com.google.android.gms.wearable (play-services-wearable 20.0.1 is unreachable here).
// There is no Data Layer on the JVM: every client call completes with a failed Task, like a device without
// Play services; the app's runWearSafely paths treat that as "smartwatch sync unavailable".
@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")

package com.google.android.gms.wearable

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

class DataMap {
    private val values = LinkedHashMap<String, Any?>()
    fun putString(key: String, value: String?) { values[key] = value }
    fun putInt(key: String, value: Int) { values[key] = value }
    fun putLong(key: String, value: Long) { values[key] = value }
    fun putBoolean(key: String, value: Boolean) { values[key] = value }
    fun putDouble(key: String, value: Double) { values[key] = value }
    fun putFloat(key: String, value: Float) { values[key] = value }
    fun putByteArray(key: String, value: ByteArray?) { values[key] = value }
    fun putStringArrayList(key: String, value: ArrayList<String>?) { values[key] = value }
    fun putDataMap(key: String, value: DataMap?) { values[key] = value }
    fun getString(key: String): String? = values[key] as? String
    fun getString(key: String, defaultValue: String): String = values[key] as? String ?: defaultValue
    fun getInt(key: String): Int = (values[key] as? Number)?.toInt() ?: 0
    fun getInt(key: String, defaultValue: Int): Int = (values[key] as? Number)?.toInt() ?: defaultValue
    fun getLong(key: String): Long = (values[key] as? Number)?.toLong() ?: 0L
    fun getLong(key: String, defaultValue: Long): Long = (values[key] as? Number)?.toLong() ?: defaultValue
    fun getBoolean(key: String): Boolean = values[key] as? Boolean ?: false
    fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
    fun getDouble(key: String): Double = (values[key] as? Number)?.toDouble() ?: 0.0
    fun getByteArray(key: String): ByteArray? = values[key] as? ByteArray
    fun getStringArrayList(key: String): ArrayList<String>? = @Suppress("UNCHECKED_CAST") (values[key] as? ArrayList<String>)
    fun getDataMap(key: String): DataMap? = values[key] as? DataMap
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = values[key] as T?
    fun keySet(): Set<String> = values.keys.toSet()
    fun containsKey(key: String): Boolean = values.containsKey(key)
    fun remove(key: String): Any? = values.remove(key)
    fun size(): Int = values.size
    fun isEmpty(): Boolean = values.isEmpty()
    fun putAll(other: DataMap) { values.putAll(other.values) }
    override fun toString(): String = "DataMap$values"
}

interface DataItem {
    val uri: Uri
    val data: ByteArray?
    fun freeze(): DataItem = this
}

/** DataItem produced by [DataClient.putDataItem] in this harness; it keeps the DataMap for [DataMapItem]. */
class HarnessDataItem(override val uri: Uri, override val data: ByteArray?, val dataMap: DataMap?) : DataItem

class DataMapItem private constructor(val uri: Uri, val dataMap: DataMap) {
    companion object {
        @JvmStatic fun fromDataItem(dataItem: DataItem): DataMapItem =
            DataMapItem(dataItem.uri, (dataItem as? HarnessDataItem)?.dataMap ?: DataMap())
    }
}

class PutDataRequest private constructor(val uri: Uri, internal val dataMap: DataMap?) {
    var data: ByteArray? = null
    var isUrgent: Boolean = false
        private set
    fun setData(data: ByteArray?): PutDataRequest { this.data = data; return this }
    fun setUrgent(): PutDataRequest { isUrgent = true; return this }

    companion object {
        const val WEAR_URI_SCHEME = "wear"
        @JvmStatic fun create(path: String): PutDataRequest = PutDataRequest(Uri.parse("wear://*$path"), null)
        @JvmStatic fun createWithAutoAppendedId(pathPrefix: String): PutDataRequest = create(pathPrefix)
        internal fun fromDataMap(uri: Uri, dataMap: DataMap): PutDataRequest = PutDataRequest(uri, dataMap)
    }
}

class PutDataMapRequest private constructor(val uri: Uri) {
    val dataMap: DataMap = DataMap()
    private var urgent = false
    fun setUrgent(): PutDataMapRequest { urgent = true; return this }
    fun asPutDataRequest(): PutDataRequest = PutDataRequest.fromDataMap(uri, dataMap).also { if (urgent) it.setUrgent() }

    companion object {
        @JvmStatic fun create(path: String): PutDataMapRequest = PutDataMapRequest(Uri.parse("wear://*$path"))
        @JvmStatic fun createWithAutoAppendedId(pathPrefix: String): PutDataMapRequest = create(pathPrefix)
        @JvmStatic fun createFromDataMapItem(item: DataMapItem): PutDataMapRequest =
            PutDataMapRequest(item.uri).also { it.dataMap.putAll(item.dataMap) }
    }
}

abstract class DataEvent {
    abstract val type: Int
    abstract val dataItem: DataItem

    companion object {
        const val TYPE_CHANGED = 1
        const val TYPE_DELETED = 2
    }
}

class DataEventBuffer(private val events: List<DataEvent>) : Iterable<DataEvent>, java.io.Closeable {
    val count: Int get() = events.size
    operator fun get(position: Int): DataEvent = events[position]
    override fun iterator(): Iterator<DataEvent> = events.iterator()
    fun release() {}
    override fun close() {}
}

interface MessageEvent {
    val requestId: Int
    val path: String
    val data: ByteArray
    val sourceNodeId: String
}

interface Node {
    val id: String
    val displayName: String
    val isNearby: Boolean
}

interface CapabilityInfo {
    val name: String
    val nodes: Set<Node>
}

private fun <T> unavailable(): Task<T> =
    Tasks.forException(UnsupportedOperationException("Wear Data Layer is not available on the JVM harness"))

abstract class DataClient {
    abstract fun putDataItem(request: PutDataRequest): Task<DataItem>
    abstract fun deleteDataItems(uri: Uri): Task<Int>
    abstract fun deleteDataItems(uri: Uri, filterType: Int): Task<Int>

    companion object {
        const val FILTER_LITERAL = 0
        const val FILTER_PREFIX = 1
    }
}

abstract class MessageClient {
    abstract fun sendMessage(nodeId: String, path: String, data: ByteArray?): Task<Int>
}

abstract class NodeClient {
    abstract val connectedNodes: Task<List<Node>>
    abstract val localNode: Task<Node>
}

abstract class CapabilityClient {
    abstract fun getCapability(capability: String, nodeFilter: Int): Task<CapabilityInfo>
    abstract fun getAllCapabilities(nodeFilter: Int): Task<Map<String, CapabilityInfo>>

    companion object {
        const val FILTER_REACHABLE = 0
        const val FILTER_ALL = 1
    }
}

private object HarnessDataClient : DataClient() {
    override fun putDataItem(request: PutDataRequest): Task<DataItem> = unavailable()
    override fun deleteDataItems(uri: Uri): Task<Int> = unavailable()
    override fun deleteDataItems(uri: Uri, filterType: Int): Task<Int> = unavailable()
}

private object HarnessMessageClient : MessageClient() {
    override fun sendMessage(nodeId: String, path: String, data: ByteArray?): Task<Int> = unavailable()
}

private object HarnessNodeClient : NodeClient() {
    override val connectedNodes: Task<List<Node>> get() = unavailable()
    override val localNode: Task<Node> get() = unavailable()
}

private object HarnessCapabilityClient : CapabilityClient() {
    override fun getCapability(capability: String, nodeFilter: Int): Task<CapabilityInfo> = unavailable()
    override fun getAllCapabilities(nodeFilter: Int): Task<Map<String, CapabilityInfo>> = unavailable()
}

object Wearable {
    @JvmStatic fun getDataClient(context: Context): DataClient = HarnessDataClient
    @JvmStatic fun getMessageClient(context: Context): MessageClient = HarnessMessageClient
    @JvmStatic fun getNodeClient(context: Context): NodeClient = HarnessNodeClient
    @JvmStatic fun getCapabilityClient(context: Context): CapabilityClient = HarnessCapabilityClient
}

abstract class WearableListenerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    open fun onDataChanged(dataEvents: DataEventBuffer) {}
    open fun onMessageReceived(messageEvent: MessageEvent) {}
    open fun onPeerConnected(peer: Node) {}
    open fun onPeerDisconnected(peer: Node) {}
}
