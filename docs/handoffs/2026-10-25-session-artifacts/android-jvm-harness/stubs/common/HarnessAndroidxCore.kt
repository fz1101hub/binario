// Harness compile stubs for androidx.core (core-ktx 1.15.0 is Google-Maven-only and unreachable here).
// Only the members the Binario sources use are modelled; behaviour delegates to the framework where possible.
@file:Suppress("unused", "PackageDirectoryMismatch")

package androidx.core.content

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.Executor

inline fun SharedPreferences.edit(commit: Boolean = false, action: SharedPreferences.Editor.() -> Unit) {
    val editor = edit()
    action(editor)
    if (commit) editor.commit() else editor.apply()
}

object ContextCompat {
    @JvmStatic fun checkSelfPermission(context: Context, permission: String): Int = context.checkSelfPermission(permission)
    @JvmStatic fun getColor(context: Context, id: Int): Int = context.getColor(id)
    @JvmStatic fun getMainExecutor(context: Context): Executor = context.mainExecutor
    @JvmStatic fun <T> getSystemService(context: Context, serviceClass: Class<T>): T? = context.getSystemService(serviceClass)
    @JvmStatic fun getString(context: Context, id: Int): String = context.getString(id)
}
