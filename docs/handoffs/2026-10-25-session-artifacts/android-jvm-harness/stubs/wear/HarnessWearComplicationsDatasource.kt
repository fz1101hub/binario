// Harness compile stubs for androidx.wear.watchface.complications.datasource 1.3.0 (Google-Maven-only, unreachable).
@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package androidx.wear.watchface.complications.datasource

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType

class ComplicationRequest(
    val complicationInstanceId: Int,
    val complicationType: ComplicationType,
    val immediateResponseRequired: Boolean = false,
)

abstract class SuspendingComplicationDataSourceService : Service() {
    abstract suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData?
    abstract fun getPreviewData(type: ComplicationType): ComplicationData?
    open fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {}
    open fun onComplicationDeactivated(complicationInstanceId: Int) {}
    override fun onBind(intent: Intent?): IBinder? = null
}

interface ComplicationDataSourceUpdateRequester {
    fun requestUpdateAll()
    fun requestUpdate(vararg complicationInstanceIds: Int)

    companion object {
        /** Recorded update requests (no watch face host on the JVM). */
        val updateRequests: MutableList<ComponentName> = mutableListOf()

        @JvmStatic fun create(context: Context, complicationDataSourceComponent: ComponentName): ComplicationDataSourceUpdateRequester =
            object : ComplicationDataSourceUpdateRequester {
                override fun requestUpdateAll() { updateRequests += complicationDataSourceComponent }
                override fun requestUpdate(vararg complicationInstanceIds: Int) { updateRequests += complicationDataSourceComponent }
            }
    }
}
