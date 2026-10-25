// Harness compile stubs for androidx.wear.tiles 1.6.2 (Google-Maven-only, unreachable here).
@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")

package androidx.wear.tiles

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import com.google.common.util.concurrent.ListenableFuture

object RequestBuilders {
    class TileRequest internal constructor(val tileId: Int, val deviceConfiguration: DeviceParametersBuilders.DeviceParameters) {
        class Builder {
            private var tileId = 0
            private var deviceConfiguration = DeviceParametersBuilders.DeviceParameters.Builder().build()
            fun setTileId(tileId: Int): Builder { this.tileId = tileId; return this }
            fun setDeviceConfiguration(deviceConfiguration: DeviceParametersBuilders.DeviceParameters): Builder { this.deviceConfiguration = deviceConfiguration; return this }
            fun build(): TileRequest = TileRequest(tileId, deviceConfiguration)
        }
    }

    class ResourcesRequest internal constructor(val version: String, val tileId: Int) {
        class Builder {
            private var version = ""
            private var tileId = 0
            fun setVersion(version: String): Builder { this.version = version; return this }
            fun setTileId(tileId: Int): Builder { this.tileId = tileId; return this }
            fun build(): ResourcesRequest = ResourcesRequest(version, tileId)
        }
    }
}

object TileBuilders {
    class Tile internal constructor(
        val resourcesVersion: String,
        val freshnessIntervalMillis: Long,
        val tileTimeline: TimelineBuilders.Timeline?,
    ) {
        class Builder {
            private var resourcesVersion = ""
            private var freshnessIntervalMillis = 0L
            private var tileTimeline: TimelineBuilders.Timeline? = null
            fun setResourcesVersion(resourcesVersion: String): Builder { this.resourcesVersion = resourcesVersion; return this }
            fun setFreshnessIntervalMillis(freshnessIntervalMillis: Long): Builder { this.freshnessIntervalMillis = freshnessIntervalMillis; return this }
            fun setTileTimeline(tileTimeline: TimelineBuilders.Timeline): Builder { this.tileTimeline = tileTimeline; return this }
            fun build(): Tile = Tile(resourcesVersion, freshnessIntervalMillis, tileTimeline)
        }
    }
}

interface TileUpdateRequester {
    fun requestUpdate(tileService: Class<out Service>)
}

abstract class TileService : Service() {
    abstract fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile>
    abstract fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources>
    open fun onTileAddEvent(requestParams: Any) {}
    open fun onTileRemoveEvent(requestParams: Any) {}
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Recorded update requests (no Wear OS renderer on the JVM). */
        val updateRequests: MutableList<Class<out Service>> = mutableListOf()

        @JvmStatic fun getUpdater(context: Context): TileUpdateRequester = object : TileUpdateRequester {
            override fun requestUpdate(tileService: Class<out Service>) { updateRequests += tileService }
        }
    }
}
