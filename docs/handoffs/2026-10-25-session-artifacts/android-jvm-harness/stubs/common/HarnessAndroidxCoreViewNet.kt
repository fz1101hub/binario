// Harness compile stubs for androidx.core.view.WindowCompat and androidx.core.net.toUri (core-ktx unreachable here).
@file:Suppress("unused", "PackageDirectoryMismatch")

package androidx.core.view

import android.view.View
import android.view.Window

class WindowInsetsControllerCompat(private val window: Window?, private val view: View?) {
    var isAppearanceLightStatusBars: Boolean = false
    var isAppearanceLightNavigationBars: Boolean = false
    fun show(types: Int) {}
    fun hide(types: Int) {}
}

object WindowCompat {
    @JvmStatic fun getInsetsController(window: Window, view: View): WindowInsetsControllerCompat =
        WindowInsetsControllerCompat(window, view)
    @JvmStatic fun setDecorFitsSystemWindows(window: Window, decorFitsSystemWindows: Boolean) {}
}
