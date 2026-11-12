package it.binario.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Store work of a view model (decoding, encoding and writing a large preferences value) taken off the main thread,
 * one change at a time in the order the changes were asked: each change runs on [worker] from the state the one
 * before it published ([read]), and its result is published ([publish]) on the dispatcher of [scope] before the next
 * change starts, so no change is lost to another one running at the same time.
 */
internal class SerialStoreWork<S>(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val read: () -> S,
    private val publish: (S) -> Unit
) {
    private val mutex = Mutex()

    fun submit(change: (S) -> S): Job = scope.launch {
        mutex.withLock {
            val current = read()
            publish(withContext(worker) { change(current) })
        }
    }
}
