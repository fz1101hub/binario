// Harness compile stubs for androidx.activity / activity-compose (Google-Maven-only, unreachable here).
// ComponentActivity is a plain android.app.Activity; Compose entry points keep the real signatures.
@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package androidx.activity

import android.app.Activity
import android.content.res.Configuration
import android.content.res.Resources

open class ComponentActivity : Activity()

class SystemBarStyle private constructor(
    val lightScrim: Int,
    val darkScrim: Int,
    val detectDarkMode: (Resources) -> Boolean,
) {
    companion object {
        @JvmStatic fun auto(
            lightScrim: Int,
            darkScrim: Int,
            detectDarkMode: (Resources) -> Boolean = { resources ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            },
        ): SystemBarStyle = SystemBarStyle(lightScrim, darkScrim, detectDarkMode)
        @JvmStatic fun dark(scrim: Int): SystemBarStyle = SystemBarStyle(scrim, scrim) { true }
        @JvmStatic fun light(scrim: Int, darkScrim: Int): SystemBarStyle = SystemBarStyle(scrim, darkScrim) { false }
    }
}

fun ComponentActivity.enableEdgeToEdge(
    statusBarStyle: SystemBarStyle = SystemBarStyle.auto(0x1b000000, 0x80000000.toInt()),
    navigationBarStyle: SystemBarStyle = SystemBarStyle.auto(0x1b000000, 0x80000000.toInt()),
) {
    // No window decoration on the JVM; a real device would configure translucent system bars here.
}
