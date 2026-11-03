// Harness compile stubs: the Android-only composition locals of androidx.compose.ui (ui-android). The JetBrains
// ui-desktop jar used on the classpath has no LocalContext/LocalConfiguration/LocalResources/LocalView.
@file:Suppress("unused", "PackageDirectoryMismatch")

package androidx.compose.ui.platform

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.view.View
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext: ProvidableCompositionLocal<Context> =
    staticCompositionLocalOf { error("CompositionLocal LocalContext not present") }

val LocalConfiguration: ProvidableCompositionLocal<Configuration> =
    compositionLocalOf(neverEqualPolicy()) { error("CompositionLocal LocalConfiguration not present") }

val LocalResources: ProvidableCompositionLocal<Resources> =
    compositionLocalOf(neverEqualPolicy()) { error("CompositionLocal LocalResources not present") }

val LocalView: ProvidableCompositionLocal<View> =
    staticCompositionLocalOf { error("CompositionLocal LocalView not present") }
