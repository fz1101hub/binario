// Harness compile stubs for androidx.activity.compose (setContent, BackHandler, rememberLauncherForActivityResult).
// Compiled with the Compose compiler plugin against the JetBrains Compose runtime; nothing is ever composed on the JVM.
@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

fun ComponentActivity.setContent(parent: CompositionContext? = null, content: @Composable () -> Unit) {
    // A real build creates a ComposeView and sets it as the activity content view.
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    rememberUpdatedState(onBack)
}

class ManagedActivityResultLauncher<I, O> internal constructor(
    private val contract: ActivityResultContract<I, O>,
    private val onResult: (O) -> Unit,
) : ActivityResultLauncher<I>() {
    override fun launch(input: I) {
        // No Activity to launch on the JVM.
    }
}

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> {
    val currentOnResult = rememberUpdatedState(onResult)
    return remember(contract) { ManagedActivityResultLauncher(contract) { currentOnResult.value(it) } }
}
