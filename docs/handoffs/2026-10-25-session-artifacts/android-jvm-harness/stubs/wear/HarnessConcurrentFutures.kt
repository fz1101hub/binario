// Harness compile stubs for androidx.concurrent.futures.CallbackToFutureAdapter (concurrent-futures 1.3.0 is
// Google-Maven-only). ListenableFuture itself comes from com.google.guava:listenablefuture:1.0 (Maven Central).
@file:Suppress("unused", "PackageDirectoryMismatch")

package androidx.concurrent.futures

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Minimal settable ListenableFuture backed by a CompletableFuture. */
class HarnessSettableFuture<T> : ListenableFuture<T> {
    private val delegate = CompletableFuture<T>()

    fun set(value: T): Boolean = delegate.complete(value)
    fun setException(throwable: Throwable): Boolean = delegate.completeExceptionally(throwable)
    override fun addListener(listener: Runnable, executor: Executor) { delegate.whenComplete { _, _ -> executor.execute(listener) } }
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = delegate.cancel(mayInterruptIfRunning)
    override fun isCancelled(): Boolean = delegate.isCancelled
    override fun isDone(): Boolean = delegate.isDone
    @Throws(InterruptedException::class, ExecutionException::class)
    override fun get(): T = delegate.get()
    @Throws(InterruptedException::class, ExecutionException::class, TimeoutException::class)
    override fun get(timeout: Long, unit: TimeUnit): T = delegate.get(timeout, unit)
}

object CallbackToFutureAdapter {
    fun interface Resolver<T> {
        @Throws(Exception::class)
        fun attachCompleter(completer: Completer<T>): Any?
    }

    class Completer<T> internal constructor(private val future: HarnessSettableFuture<T>) {
        fun set(value: T): Boolean = future.set(value)
        fun setException(t: Throwable): Boolean = future.setException(t)
        fun setCancelled(): Boolean = future.cancel(false)
        fun addCancellationListener(runnable: Runnable, executor: Executor) { future.addListener(runnable, executor) }
    }

    @JvmStatic
    fun <T> getFuture(callback: Resolver<T>): ListenableFuture<T> {
        val future = HarnessSettableFuture<T>()
        try {
            callback.attachCompleter(Completer(future))
        } catch (e: Exception) {
            future.setException(e)
        }
        return future
    }
}
