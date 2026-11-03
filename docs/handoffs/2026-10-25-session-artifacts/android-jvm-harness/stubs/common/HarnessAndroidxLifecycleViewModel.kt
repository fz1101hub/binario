// Harness compile stubs for androidx.lifecycle.viewmodel (CreationExtras, viewModelFactory { initializer { } }).
@file:Suppress("unused", "PackageDirectoryMismatch")

package androidx.lifecycle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

abstract class CreationExtras internal constructor() {
    interface Key<T>

    internal val map = LinkedHashMap<Key<*>, Any?>()

    @Suppress("UNCHECKED_CAST")
    open operator fun <T> get(key: Key<T>): T? = map[key] as T?

    object Empty : CreationExtras()
}

class MutableCreationExtras(initialExtras: CreationExtras = Empty) : CreationExtras() {
    init { map.putAll(initialExtras.map) }
    operator fun <T> set(key: Key<T>, t: T) { map[key] = t }
}

class ViewModelInitializer<T : ViewModel>(
    internal val clazz: Class<T>,
    internal val initializer: CreationExtras.() -> T,
)

class InitializerViewModelFactory(private vararg val initializers: ViewModelInitializer<*>) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        for (initializer in initializers) {
            if (initializer.clazz == modelClass) return initializer.initializer(extras) as T
        }
        throw IllegalArgumentException("No initializer set for given class ${modelClass.name}")
    }

    override fun <T : ViewModel> create(modelClass: Class<T>): T = create(modelClass, CreationExtras.Empty)
}

class InitializerViewModelFactoryBuilder {
    private val initializers = mutableListOf<ViewModelInitializer<*>>()

    fun <T : ViewModel> addInitializer(clazz: Class<T>, initializer: CreationExtras.() -> T) {
        initializers += ViewModelInitializer(clazz, initializer)
    }

    fun build(): ViewModelProvider.Factory = InitializerViewModelFactory(*initializers.toTypedArray())
}

inline fun <reified VM : ViewModel> InitializerViewModelFactoryBuilder.initializer(noinline initializer: CreationExtras.() -> VM) {
    addInitializer(VM::class.java, initializer)
}

inline fun viewModelFactory(builder: InitializerViewModelFactoryBuilder.() -> Unit): ViewModelProvider.Factory =
    InitializerViewModelFactoryBuilder().apply(builder).build()
