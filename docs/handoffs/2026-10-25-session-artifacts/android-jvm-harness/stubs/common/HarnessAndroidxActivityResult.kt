// Harness compile stubs for androidx.activity.result (+ .contract). Launching never reaches a real Activity.
@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package androidx.activity.result

import android.content.Intent

class ActivityResult(val resultCode: Int, val data: Intent?)

fun interface ActivityResultCallback<O> {
    fun onActivityResult(result: O)
}

abstract class ActivityResultLauncher<I> {
    abstract fun launch(input: I)
    open fun unregister() {}
}
