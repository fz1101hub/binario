// Harness compile stubs for androidx.work (work-runtime-ktx 2.10.0 is Google-Maven-only, unreachable here).
// WorkManager records enqueued requests in memory instead of scheduling anything.
@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")

package androidx.work

import android.content.Context
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

class Data(private val values: Map<String, Any?>) {
    fun getString(key: String): String? = values[key] as? String
    fun getInt(key: String, defaultValue: Int): Int = (values[key] as? Number)?.toInt() ?: defaultValue
    fun getLong(key: String, defaultValue: Long): Long = (values[key] as? Number)?.toLong() ?: defaultValue
    fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
    val keyValueMap: Map<String, Any?> get() = values

    class Builder {
        private val values = LinkedHashMap<String, Any?>()
        fun putString(key: String, value: String?): Builder { values[key] = value; return this }
        fun putInt(key: String, value: Int): Builder { values[key] = value; return this }
        fun putLong(key: String, value: Long): Builder { values[key] = value; return this }
        fun putBoolean(key: String, value: Boolean): Builder { values[key] = value; return this }
        fun putAll(data: Data): Builder { values.putAll(data.values); return this }
        fun build(): Data = Data(values.toMap())
    }

    companion object {
        @JvmField val EMPTY: Data = Data(emptyMap())
    }
}

fun workDataOf(vararg pairs: Pair<String, Any?>): Data = Data(mapOf(*pairs))

class WorkerParameters(val inputData: Data = Data.EMPTY, val id: UUID = UUID.randomUUID(), val runAttemptCount: Int = 0)

abstract class ListenableWorker(val applicationContext: Context, private val workerParams: WorkerParameters) {
    val inputData: Data get() = workerParams.inputData
    val id: UUID get() = workerParams.id
    val runAttemptCount: Int get() = workerParams.runAttemptCount
    open fun onStopped() {}

    abstract class Result internal constructor(val outputData: Data) {
        class Success(outputData: Data) : Result(outputData)
        class Retry : Result(Data.EMPTY)
        class Failure(outputData: Data) : Result(outputData)

        companion object {
            @JvmStatic fun success(): Result = Success(Data.EMPTY)
            @JvmStatic fun success(outputData: Data): Result = Success(outputData)
            @JvmStatic fun retry(): Result = Retry()
            @JvmStatic fun failure(): Result = Failure(Data.EMPTY)
            @JvmStatic fun failure(outputData: Data): Result = Failure(outputData)
        }
    }
}

abstract class CoroutineWorker(appContext: Context, params: WorkerParameters) : ListenableWorker(appContext, params) {
    abstract suspend fun doWork(): Result
}

enum class NetworkType { NOT_REQUIRED, CONNECTED, UNMETERED, NOT_ROAMING, METERED, TEMPORARILY_UNMETERED }
enum class BackoffPolicy { EXPONENTIAL, LINEAR }
enum class ExistingWorkPolicy { REPLACE, KEEP, APPEND, APPEND_OR_REPLACE }
enum class ExistingPeriodicWorkPolicy { REPLACE, KEEP, UPDATE, CANCEL_AND_REENQUEUE }

class Constraints(
    val requiredNetworkType: NetworkType = NetworkType.NOT_REQUIRED,
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = false,
    val requiresStorageNotLow: Boolean = false,
) {
    class Builder {
        private var networkType = NetworkType.NOT_REQUIRED
        private var charging = false
        private var batteryNotLow = false
        private var storageNotLow = false
        fun setRequiredNetworkType(networkType: NetworkType): Builder { this.networkType = networkType; return this }
        fun setRequiresCharging(requiresCharging: Boolean): Builder { charging = requiresCharging; return this }
        fun setRequiresBatteryNotLow(requiresBatteryNotLow: Boolean): Builder { batteryNotLow = requiresBatteryNotLow; return this }
        fun setRequiresStorageNotLow(requiresStorageNotLow: Boolean): Builder { storageNotLow = requiresStorageNotLow; return this }
        fun build(): Constraints = Constraints(networkType, charging, batteryNotLow, storageNotLow)
    }

    companion object {
        @JvmField val NONE: Constraints = Constraints()
    }
}

abstract class WorkRequest internal constructor(
    val id: UUID,
    val workerClass: Class<out ListenableWorker>,
    val constraints: Constraints,
    val inputData: Data,
    val tags: Set<String>,
    val backoffPolicy: BackoffPolicy,
    val backoffDelayMillis: Long,
    val initialDelayMillis: Long,
) {
    abstract class Builder<B : Builder<B, W>, W : WorkRequest> internal constructor(internal val workerClass: Class<out ListenableWorker>) {
        internal var constraints: Constraints = Constraints.NONE
        internal var inputData: Data = Data.EMPTY
        internal val tags = LinkedHashSet<String>()
        internal var backoffPolicy = BackoffPolicy.EXPONENTIAL
        internal var backoffDelayMillis = 30_000L
        internal var initialDelayMillis = 0L
        internal val id: UUID = UUID.randomUUID()

        @Suppress("UNCHECKED_CAST")
        private fun self(): B = this as B
        fun setConstraints(constraints: Constraints): B { this.constraints = constraints; return self() }
        fun setInputData(inputData: Data): B { this.inputData = inputData; return self() }
        fun addTag(tag: String): B { tags += tag; return self() }
        fun setBackoffCriteria(backoffPolicy: BackoffPolicy, backoffDelay: Long, timeUnit: TimeUnit): B {
            this.backoffPolicy = backoffPolicy; backoffDelayMillis = timeUnit.toMillis(backoffDelay); return self()
        }
        fun setBackoffCriteria(backoffPolicy: BackoffPolicy, duration: Duration): B {
            this.backoffPolicy = backoffPolicy; backoffDelayMillis = duration.toMillis(); return self()
        }
        fun setInitialDelay(duration: Long, timeUnit: TimeUnit): B { initialDelayMillis = timeUnit.toMillis(duration); return self() }
        fun setInitialDelay(duration: Duration): B { initialDelayMillis = duration.toMillis(); return self() }
        abstract fun build(): W
    }
}

class OneTimeWorkRequest internal constructor(builder: Builder) : WorkRequest(
    builder.id, builder.workerClass, builder.constraints, builder.inputData, builder.tags,
    builder.backoffPolicy, builder.backoffDelayMillis, builder.initialDelayMillis,
) {
    class Builder(workerClass: Class<out ListenableWorker>) : WorkRequest.Builder<Builder, OneTimeWorkRequest>(workerClass) {
        override fun build(): OneTimeWorkRequest = OneTimeWorkRequest(this)
    }
}

class PeriodicWorkRequest internal constructor(builder: Builder, val intervalMillis: Long) : WorkRequest(
    builder.id, builder.workerClass, builder.constraints, builder.inputData, builder.tags,
    builder.backoffPolicy, builder.backoffDelayMillis, builder.initialDelayMillis,
) {
    class Builder(workerClass: Class<out ListenableWorker>, private val intervalMillis: Long) : WorkRequest.Builder<Builder, PeriodicWorkRequest>(workerClass) {
        constructor(workerClass: Class<out ListenableWorker>, repeatInterval: Long, repeatIntervalTimeUnit: TimeUnit) :
            this(workerClass, repeatIntervalTimeUnit.toMillis(repeatInterval))
        constructor(workerClass: Class<out ListenableWorker>, repeatInterval: Duration) : this(workerClass, repeatInterval.toMillis())
        override fun build(): PeriodicWorkRequest = PeriodicWorkRequest(this, intervalMillis)
    }

    companion object {
        const val MIN_PERIODIC_INTERVAL_MILLIS: Long = 15 * 60 * 1000L
    }
}

inline fun <reified W : ListenableWorker> OneTimeWorkRequestBuilder(): OneTimeWorkRequest.Builder = OneTimeWorkRequest.Builder(W::class.java)
inline fun <reified W : ListenableWorker> PeriodicWorkRequestBuilder(repeatInterval: Long, repeatIntervalTimeUnit: TimeUnit): PeriodicWorkRequest.Builder =
    PeriodicWorkRequest.Builder(W::class.java, repeatInterval, repeatIntervalTimeUnit)
inline fun <reified W : ListenableWorker> PeriodicWorkRequestBuilder(repeatInterval: Duration): PeriodicWorkRequest.Builder =
    PeriodicWorkRequest.Builder(W::class.java, repeatInterval)

class Operation internal constructor()

class WorkManager private constructor() {
    /** Everything enqueued through this JVM process; nothing is executed. */
    val enqueued: MutableList<Pair<String?, WorkRequest>> = mutableListOf()

    fun enqueue(request: WorkRequest): Operation { enqueued += null to request; return Operation() }
    fun enqueueUniqueWork(uniqueWorkName: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest): Operation {
        if (policy == ExistingWorkPolicy.REPLACE) enqueued.removeAll { it.first == uniqueWorkName }
        enqueued += uniqueWorkName to request; return Operation()
    }
    fun enqueueUniquePeriodicWork(uniqueWorkName: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest): Operation {
        if (policy == ExistingPeriodicWorkPolicy.KEEP && enqueued.any { it.first == uniqueWorkName }) return Operation()
        enqueued.removeAll { it.first == uniqueWorkName }
        enqueued += uniqueWorkName to request; return Operation()
    }
    fun cancelUniqueWork(uniqueWorkName: String): Operation { enqueued.removeAll { it.first == uniqueWorkName }; return Operation() }
    fun cancelAllWorkByTag(tag: String): Operation { enqueued.removeAll { tag in it.second.tags }; return Operation() }
    fun cancelAllWork(): Operation { enqueued.clear(); return Operation() }

    companion object {
        private val instance = WorkManager()
        @JvmStatic fun getInstance(context: Context): WorkManager = instance
    }
}
