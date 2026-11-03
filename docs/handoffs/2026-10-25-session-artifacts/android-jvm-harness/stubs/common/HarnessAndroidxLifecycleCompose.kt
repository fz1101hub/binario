// Harness compile stubs for androidx.lifecycle.compose (lifecycle-runtime-compose): LocalLifecycleOwner and
// LifecycleEventEffect. Nothing is composed on the JVM, so the effect never fires.
@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package androidx.lifecycle.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

val LocalLifecycleOwner: ProvidableCompositionLocal<LifecycleOwner> =
    staticCompositionLocalOf { error("CompositionLocal LocalLifecycleOwner not present") }

@Composable
fun LifecycleEventEffect(
    event: Lifecycle.Event,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onEvent: () -> Unit,
) {
    require(event != Lifecycle.Event.ON_DESTROY) { "LifecycleEventEffect cannot be used to listen for Lifecycle.Event.ON_DESTROY." }
    val currentOnEvent = rememberUpdatedState(onEvent)
    DisposableEffect(lifecycleOwner, event) {
        // The real effect registers a LifecycleEventObserver; there is no lifecycle to observe on the JVM.
        onDispose { currentOnEvent.value.hashCode() }
    }
}

@Composable
fun LifecycleStartEffect(
    key1: Any?,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    effects: () -> Unit,
) {
    DisposableEffect(lifecycleOwner, key1) { onDispose { } }
}
