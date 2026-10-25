// Harness compile stubs for com.google.android.gms.tasks (play-services is Google-Maven-only, unreachable here).
// Tasks complete synchronously; listeners added to a completed task fire immediately.
@file:Suppress("unused", "PackageDirectoryMismatch")

package com.google.android.gms.tasks

fun interface OnSuccessListener<T> { fun onSuccess(result: T) }
fun interface OnFailureListener { fun onFailure(exception: Exception) }
fun interface OnCanceledListener { fun onCanceled() }
fun interface OnCompleteListener<T> { fun onComplete(task: Task<T>) }

abstract class Task<T> {
    abstract val isComplete: Boolean
    abstract val isSuccessful: Boolean
    abstract val isCanceled: Boolean
    abstract val result: T?
    abstract val exception: Exception?
    abstract fun addOnSuccessListener(listener: OnSuccessListener<in T>): Task<T>
    abstract fun addOnFailureListener(listener: OnFailureListener): Task<T>
    abstract fun addOnCanceledListener(listener: OnCanceledListener): Task<T>
    abstract fun addOnCompleteListener(listener: OnCompleteListener<T>): Task<T>
}

class HarnessTask<T> private constructor(
    override val result: T?,
    override val exception: Exception?,
    override val isCanceled: Boolean,
) : Task<T>() {
    override val isComplete: Boolean get() = true
    override val isSuccessful: Boolean get() = exception == null && !isCanceled

    @Suppress("UNCHECKED_CAST")
    override fun addOnSuccessListener(listener: OnSuccessListener<in T>): Task<T> {
        if (isSuccessful) listener.onSuccess(result as T)
        return this
    }
    override fun addOnFailureListener(listener: OnFailureListener): Task<T> {
        exception?.let(listener::onFailure)
        return this
    }
    override fun addOnCanceledListener(listener: OnCanceledListener): Task<T> {
        if (isCanceled) listener.onCanceled()
        return this
    }
    override fun addOnCompleteListener(listener: OnCompleteListener<T>): Task<T> {
        listener.onComplete(this)
        return this
    }

    companion object {
        fun <T> success(value: T): Task<T> = HarnessTask(value, null, false)
        fun <T> failure(exception: Exception): Task<T> = HarnessTask(null, exception, false)
        fun <T> canceled(): Task<T> = HarnessTask(null, null, true)
    }
}

object Tasks {
    @JvmStatic fun <T> forResult(result: T): Task<T> = HarnessTask.success(result)
    @JvmStatic fun <T> forException(exception: Exception): Task<T> = HarnessTask.failure(exception)
    @JvmStatic fun <T> forCanceled(): Task<T> = HarnessTask.canceled()
}
