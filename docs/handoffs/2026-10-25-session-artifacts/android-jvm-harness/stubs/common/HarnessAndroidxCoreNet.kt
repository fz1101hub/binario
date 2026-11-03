// Harness compile stub for androidx.core.net.toUri (core-ktx unreachable here).
@file:Suppress("unused", "PackageDirectoryMismatch")

package androidx.core.net

import android.net.Uri
import java.io.File

fun String.toUri(): Uri = Uri.parse(this)
fun File.toUri(): Uri = Uri.fromFile(this)
