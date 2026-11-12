package it.binario.app.data.live

import android.content.Context
import androidx.core.content.edit
import java.time.Duration
import java.time.Instant

/**
 * The refusal pauses of the public sources, kept across process death: a background run (widget refresh, worker,
 * alarm receiver) often starts in a new process, and «stop at the first 403/429» must hold there too. One entry per
 * source label: the instant until which the source is not asked.
 */
internal interface RefusalPauseStore {
    /** The pause of [label] still in force at [now], at most [RefusalPauseRules.MAX_PAUSE] ahead; null when none. */
    fun read(label: String, now: Instant): Instant?

    /** Keeps the pause of [label] until [until] (capped at [RefusalPauseRules.MAX_PAUSE] from [now]). */
    fun write(label: String, until: Instant, now: Instant)
}

/** The store of this app process, set once by `BinarioApplication` before any request; null in JVM tests. */
internal object PersistedRefusals {
    @Volatile
    var store: RefusalPauseStore? = null
}

/** The pure rules of a stored pause: epoch milliseconds, never more than an hour ahead, gone once expired. */
internal object RefusalPauseRules {
    /** The longest pause the circuit breaker allows ([SourceCircuitBreaker]'s `maxRetryAfter`). */
    val MAX_PAUSE: Duration = Duration.ofHours(1)

    /** The value to store for a pause until [until] written at [now]. */
    fun encode(until: Instant, now: Instant): Long = minOf(until, now.plus(MAX_PAUSE)).toEpochMilli()

    /** A stored value still in force at [now], capped at [MAX_PAUSE] ahead (a clock set back); null when expired or unreadable. */
    fun restore(stored: Long?, now: Instant): Instant? {
        val until = stored?.takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: return null
        return if (now.isBefore(until)) minOf(until, now.plus(MAX_PAUSE)) else null
    }
}

/**
 * [RefusalPauseStore] in the private preferences `binario_source_refusals_v1` (excluded from backup and device
 * transfer, like every preference file of the app). Expired entries are dropped when read.
 */
internal class SharedPreferencesRefusalPauseStore(context: Context) : RefusalPauseStore {
    private val app = context.applicationContext
    // Opened on the first request, never during the app start.
    private val preferences by lazy { app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }

    override fun read(label: String, now: Instant): Instant? {
        val expired = preferences.all.filter { (_, value) -> RefusalPauseRules.restore(value as? Long, now) == null }.keys
        if (expired.isNotEmpty()) preferences.edit { expired.forEach(::remove) }
        return RefusalPauseRules.restore(preferences.getLong(label, 0L), now)
    }

    override fun write(label: String, until: Instant, now: Instant) {
        preferences.edit { putLong(label, RefusalPauseRules.encode(until, now)) }
    }

    private companion object {
        const val PREFERENCES = "binario_source_refusals_v1"
    }
}
