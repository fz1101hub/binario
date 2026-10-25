// Harness compile stub: androidx.lifecycle.AndroidViewModel is Android-only; the JetBrains lifecycle-viewmodel-desktop
// jar (Maven Central) supplies ViewModel, viewModelScope, SavedStateHandle, viewModelFactory and createSavedStateHandle.
@file:Suppress("unused", "PackageDirectoryMismatch")

package androidx.lifecycle

import android.app.Application

open class AndroidViewModel(private val application: Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    open fun <T : Application> getApplication(): T = application as T
}
