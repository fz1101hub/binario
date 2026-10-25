// Harness compile stubs for androidx.lifecycle.viewmodel.compose (viewModel(), LocalViewModelStoreOwner).
@file:Suppress("unused", "PackageDirectoryMismatch")

package androidx.lifecycle.viewmodel.compose

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras

object LocalViewModelStoreOwner {
    private val local: ProvidableCompositionLocal<ViewModelStoreOwner?> = compositionLocalOf { null }

    val current: ViewModelStoreOwner?
        @Composable get() = local.current

    infix fun provides(viewModelStoreOwner: ViewModelStoreOwner) = local provides viewModelStoreOwner
}

@Composable
inline fun <reified VM : ViewModel> viewModel(
    viewModelStoreOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
    },
    key: String? = null,
    factory: ViewModelProvider.Factory? = null,
    extras: CreationExtras = if (viewModelStoreOwner is HasDefaultViewModelProviderFactory) {
        viewModelStoreOwner.defaultViewModelCreationExtras
    } else {
        CreationExtras.Empty
    },
): VM = viewModel(VM::class.java, viewModelStoreOwner, key, factory, extras)

@Composable
fun <VM : ViewModel> viewModel(
    modelClass: Class<VM>,
    viewModelStoreOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
    },
    key: String? = null,
    factory: ViewModelProvider.Factory? = null,
    extras: CreationExtras = CreationExtras.Empty,
): VM {
    val application = LocalContext.current.applicationContext as? Application
    val resolvedFactory = factory
        ?: (viewModelStoreOwner as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory
        ?: ViewModelProvider.AndroidViewModelFactory(application)
    val provider = ViewModelProvider(viewModelStoreOwner.viewModelStore, resolvedFactory, extras)
    return if (key != null) provider[key, modelClass] else provider[modelClass]
}

/** Convenience mirroring the Android artifact: an owner backed by a plain Context has no store on the JVM. */
internal fun Context.requireApplication(): Application =
    applicationContext as? Application ?: error("Context is not backed by an Application")
