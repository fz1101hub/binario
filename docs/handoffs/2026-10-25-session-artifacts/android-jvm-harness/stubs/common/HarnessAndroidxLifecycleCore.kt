// Harness compile stubs for the androidx.lifecycle core (lifecycle-runtime/viewmodel/viewmodel-savedstate).
// The 2.8.x KMP jars live on Google Maven only and JetBrains republished the core only for Kotlin 2.2 (2.11.0),
// so the members Binario uses are modelled here with faithful in-memory semantics (viewModelScope uses
// Dispatchers.Main.immediate like the real one, so kotlinx-coroutines-test's Dispatchers.setMain applies).
@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package androidx.lifecycle

import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ---- Lifecycle (minimal) -----------------------------------------------------------------------------------
abstract class Lifecycle {
    enum class State { DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED }
    enum class Event { ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY, ON_ANY }
    abstract val currentState: State
}

interface LifecycleOwner {
    val lifecycle: Lifecycle
}

/** The real one re-runs [block] every time the lifecycle reaches [state]; on the JVM it runs the block once. */
suspend fun Lifecycle.repeatOnLifecycle(state: Lifecycle.State, block: suspend CoroutineScope.() -> Unit) {
    require(state !== Lifecycle.State.INITIALIZED) { "repeatOnLifecycle cannot start work with the INITIALIZED lifecycle state." }
    if (currentState === Lifecycle.State.DESTROYED) return
    coroutineScope { block() }
}

suspend fun LifecycleOwner.repeatOnLifecycle(state: Lifecycle.State, block: suspend CoroutineScope.() -> Unit) =
    lifecycle.repeatOnLifecycle(state, block)

// ---- ViewModel ------------------------------------------------------------------------------------------------
open class ViewModel {
    private val closeables = LinkedHashMap<String, AutoCloseable>()
    private val anonymousCloseables = LinkedHashSet<AutoCloseable>()
    private var cleared = false

    constructor()
    constructor(vararg closeables: AutoCloseable) { anonymousCloseables.addAll(closeables) }
    constructor(viewModelScope: CoroutineScope) { addCloseable(VIEW_MODEL_SCOPE_KEY, CloseableCoroutineScope(viewModelScope)) }

    protected open fun onCleared() {}

    fun clear() {
        if (cleared) return
        cleared = true
        synchronized(closeables) {
            closeables.values.forEach(AutoCloseable::close)
            anonymousCloseables.forEach(AutoCloseable::close)
        }
        onCleared()
    }

    fun addCloseable(key: String, closeable: AutoCloseable) {
        synchronized(closeables) { closeables[key] = closeable }
        if (cleared) closeable.close()
    }

    fun addCloseable(closeable: AutoCloseable) {
        synchronized(closeables) { anonymousCloseables += closeable }
        if (cleared) closeable.close()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : AutoCloseable> getCloseable(key: String): T? = synchronized(closeables) { closeables[key] as T? }

    internal fun scope(): CoroutineScope = synchronized(closeables) {
        (closeables[VIEW_MODEL_SCOPE_KEY] as? CloseableCoroutineScope)
            ?: CloseableCoroutineScope(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { closeables[VIEW_MODEL_SCOPE_KEY] = it }
    }

    private companion object {
        const val VIEW_MODEL_SCOPE_KEY = "androidx.lifecycle.viewmodel.internal.ViewModelCoroutineScope.JOB_KEY"
    }
}

internal class CloseableCoroutineScope(private val delegate: CoroutineScope) : CoroutineScope by delegate, AutoCloseable {
    override fun close() { delegate.cancel() }
}

val ViewModel.viewModelScope: CoroutineScope
    get() = scope()

// ---- ViewModelStore / provider ---------------------------------------------------------------------------------
class ViewModelStore {
    private val map = HashMap<String, ViewModel>()
    fun put(key: String, viewModel: ViewModel) { map.put(key, viewModel)?.clear() }
    operator fun get(key: String): ViewModel? = map[key]
    fun keys(): Set<String> = map.keys.toSet()
    fun clear() { map.values.forEach(ViewModel::clear); map.clear() }
}

interface ViewModelStoreOwner {
    val viewModelStore: ViewModelStore
}

interface HasDefaultViewModelProviderFactory {
    val defaultViewModelProviderFactory: ViewModelProvider.Factory
    val defaultViewModelCreationExtras: CreationExtras get() = CreationExtras.Empty
}

open class ViewModelProvider(
    private val store: ViewModelStore,
    private val factory: Factory,
    private val defaultCreationExtras: CreationExtras = CreationExtras.Empty,
) {
    constructor(owner: ViewModelStoreOwner, factory: Factory) : this(owner.viewModelStore, factory)

    interface Factory {
        fun <T : ViewModel> create(modelClass: Class<T>): T =
            throw UnsupportedOperationException("Factory.create(modelClass) is not implemented in ${javaClass.name}")
        fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = create(modelClass)
    }

    open class NewInstanceFactory : Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.getDeclaredConstructor().newInstance()
    }

    /** Mirrors AndroidViewModelFactory: prefers a one-argument (Application) constructor. */
    class AndroidViewModelFactory(private val application: android.app.Application?) : NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (application != null && AndroidViewModel::class.java.isAssignableFrom(modelClass)) {
                return modelClass.getConstructor(android.app.Application::class.java).newInstance(application)
            }
            return super.create(modelClass)
        }
    }

    operator fun <T : ViewModel> get(modelClass: Class<T>): T = get("$DEFAULT_KEY:${modelClass.canonicalName}", modelClass)

    @Suppress("UNCHECKED_CAST")
    operator fun <T : ViewModel> get(key: String, modelClass: Class<T>): T {
        val existing = store[key]
        if (existing != null && modelClass.isInstance(existing)) return existing as T
        val created = factory.create(modelClass, defaultCreationExtras)
        store.put(key, created)
        return created
    }

    companion object {
        private const val DEFAULT_KEY = "androidx.lifecycle.ViewModelProvider.DefaultKey"
    }
}

// ---- SavedStateHandle --------------------------------------------------------------------------------------------
class SavedStateHandle(initialState: Map<String, Any?> = emptyMap()) {
    private val regular = LinkedHashMap<String, Any?>(initialState)
    private val flows = HashMap<String, MutableStateFlow<Any?>>()

    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: String): T? = synchronized(regular) { regular[key] as T? }

    operator fun <T> set(key: String, value: T?) {
        synchronized(regular) {
            regular[key] = value
            flows[key]?.value = value
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> remove(key: String): T? = synchronized(regular) {
        flows.remove(key)
        regular.remove(key) as T?
    }

    operator fun contains(key: String): Boolean = synchronized(regular) { regular.containsKey(key) }

    fun keys(): Set<String> = synchronized(regular) { regular.keys.toSet() }

    @Suppress("UNCHECKED_CAST")
    fun <T> getStateFlow(key: String, initialValue: T): StateFlow<T> = synchronized(regular) {
        val flow = flows.getOrPut(key) {
            if (!regular.containsKey(key)) regular[key] = initialValue
            MutableStateFlow(regular[key])
        }
        flow.asStateFlow() as StateFlow<T>
    }

    companion object {
        @JvmStatic fun createHandle(restoredState: Map<String, Any?>?, defaultState: Map<String, Any?>?): SavedStateHandle =
            SavedStateHandle(restoredState ?: defaultState ?: emptyMap())
    }
}

/** Real builds restore the handle from the SavedStateRegistry; the harness always starts from an empty handle. */
fun CreationExtras.createSavedStateHandle(): SavedStateHandle = SavedStateHandle()
