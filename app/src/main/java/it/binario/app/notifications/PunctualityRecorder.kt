package it.binario.app.notifications

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.binario.app.ConnectionRiskClassifier
import it.binario.app.FavouriteTrainMatcher
import it.binario.app.FavouriteTrainStore
import it.binario.app.PunctualityHistory
import it.binario.app.PunctualityHistoryStore
import it.binario.app.PunctualityObservation
import it.binario.app.ServiceMonitor
import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.data.repository.TrainNumberQuery
import it.binario.app.data.repository.TrainNumberSearchUpdate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** What the recorder knows about one favourite train: its saved departure and what it learned or did. */
data class RecordingCandidate(
    val number: String,
    val departure: LocalTime?,
    /** Minutes from the departure to the scheduled arrival at the last stop, learned from a lookup. */
    val durationMinutes: Int? = null,
    val recordedOn: LocalDate? = null,
    val attemptDate: LocalDate? = null,
    val attempts: Int = 0,
    val lastAttempt: Instant? = null
)

/**
 * When the final delay of each favourite train is recorded: once a day, [AFTER_ARRIVAL] after its scheduled
 * arrival (learned from the first lookup; before that two hours after its departure, or at [WITHOUT_DEPARTURE] for a
 * train saved without one). A failed or early attempt is retried after [RETRY], at most [MAX_ATTEMPTS] times a day.
 */
object PunctualityRecordingPlanner {
    val AFTER_ARRIVAL: Duration = Duration.ofMinutes(20)
    val FIRST_TRY_AFTER_DEPARTURE: Duration = Duration.ofHours(2)
    val WITHOUT_DEPARTURE: LocalTime = LocalTime.of(23, 15)
    val RETRY: Duration = Duration.ofMinutes(45)
    const val MAX_ATTEMPTS = 3

    /** The instant the run of [date] should be recorded, or null when it is done or out of attempts. */
    fun dueAt(candidate: RecordingCandidate, date: LocalDate, zone: ZoneId = RailTime.defaultZone): Instant? {
        if (candidate.recordedOn == date) return null
        val sameDayAttempts = if (candidate.attemptDate == date) candidate.attempts else 0
        if (sameDayAttempts >= MAX_ATTEMPTS) return null
        val planned = when {
            candidate.departure != null && candidate.durationMinutes != null ->
                LocalDateTime.of(date, candidate.departure).plusMinutes(candidate.durationMinutes.toLong()).atZone(zone).toInstant().plus(AFTER_ARRIVAL)
            candidate.departure != null -> LocalDateTime.of(date, candidate.departure).atZone(zone).toInstant().plus(FIRST_TRY_AFTER_DEPARTURE)
            else -> LocalDateTime.of(date, WITHOUT_DEPARTURE).atZone(zone).toInstant()
        }
        val retry = candidate.lastAttempt?.takeIf { sameDayAttempts > 0 }?.plus(RETRY)
        return if (retry != null && retry.isAfter(planned)) retry else planned
    }

    /** The runs to record at [now]: yesterday's late arrivals and today's, each with its service day. */
    fun due(candidates: List<RecordingCandidate>, now: Instant, zone: ZoneId = RailTime.defaultZone): List<Pair<RecordingCandidate, LocalDate>> {
        val today = now.atZone(zone).toLocalDate()
        return candidates.flatMap { candidate ->
            listOf(today.minusDays(1), today).mapNotNull { date ->
                dueAt(candidate, date, zone)?.takeIf { !it.isAfter(now) && it.isAfter(now.minus(Duration.ofHours(20))) }?.let { candidate to date }
            }
        }
    }

    /** The next run: now when one is due, else the next due instant of yesterday's (a night run), today's or tomorrow's run. */
    fun nextRun(candidates: List<RecordingCandidate>, now: Instant, zone: ZoneId = RailTime.defaultZone): Instant? {
        if (due(candidates, now, zone).isNotEmpty()) return now
        val today = now.atZone(zone).toLocalDate()
        return candidates.flatMap { candidate -> listOf(today.minusDays(1), today, today.plusDays(1)).mapNotNull { dueAt(candidate, it, zone) } }
            .filter { it.isAfter(now) }
            .minOrNull()
    }

    /**
     * True when the last answer of the search ([update], null when it failed or timed out) says the train has no run that
     * day: complete, every source answered, no match. That is final, as for the approach alert, and is not retried.
     */
    fun runsNot(update: TrainNumberSearchUpdate?): Boolean =
        update != null && update.complete && update.unavailableSources.isEmpty() && update.matches.isEmpty()

    /** True once the run has reached its last stop, or [AFTER_ARRIVAL] has passed since its scheduled arrival. */
    fun arrived(service: TrainService, scheduledArrival: Instant, now: Instant): Boolean =
        service.stops.lastOrNull()?.completed == true || !now.isBefore(scheduledArrival.plus(AFTER_ARRIVAL))

    /**
     * What the run [service] of the favourite [favouriteNumber] adds to the history for [date]: kept under the
     * favourite's own number, where «I miei treni» reads it. The live source lists "2153" the train a timetable row,
     * and the favourite saved from it, call "RE_11 - 2153".
     */
    fun observation(service: TrainService, favouriteNumber: String, date: LocalDate): PunctualityObservation? =
        PunctualityHistory.observationOf(service, date)?.copy(trainNumber = PunctualityHistory.trainKey(favouriteNumber), serviceDate = date)
}

/** Per-train state of the recorder in local preferences (never in the backup: it is only a schedule). */
internal class PunctualityRecorderStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_punctuality_recorder_v1", Context.MODE_PRIVATE)

    fun candidate(monitor: ServiceMonitor): RecordingCandidate {
        val key = PunctualityHistory.trainKey(monitor.number)
        val value = preferences.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        return RecordingCandidate(
            number = monitor.number,
            departure = runCatching { LocalTime.parse(monitor.scheduledDeparture.trim()) }.getOrNull(),
            durationMinutes = value?.optInt("duration", -1)?.takeIf { it in 1..(36 * 60) },
            recordedOn = value?.optString("recorded")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            attemptDate = value?.optString("attemptDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            attempts = value?.optInt("attempts", 0) ?: 0,
            lastAttempt = value?.optLong("lastAttempt", 0L)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        )
    }

    fun save(candidate: RecordingCandidate) = preferences.edit {
        putString(PunctualityHistory.trainKey(candidate.number), JSONObject().apply {
            candidate.durationMinutes?.let { put("duration", it) }
            candidate.recordedOn?.let { put("recorded", it.toString()) }
            candidate.attemptDate?.let { put("attemptDate", it.toString()) }
            put("attempts", candidate.attempts)
            candidate.lastAttempt?.let { put("lastAttempt", it.toEpochMilli()) }
        }.toString())
    }

    /** Forgets trains that are no longer favourites. */
    fun retainOnly(numbers: Collection<String>) {
        val keep = numbers.mapTo(hashSetOf(), PunctualityHistory::trainKey)
        val stale = preferences.all.keys.filter { it !in keep }
        if (stale.isNotEmpty()) preferences.edit { stale.forEach(::remove) }
    }
}

object PunctualityRecorderScheduler {
    internal const val WORK = "binario-punctuality-recorder"
    private val networkConnected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun reschedule(context: Context) = schedule(context, fromWorker = false)

    internal fun schedule(context: Context, fromWorker: Boolean) {
        val workManager = WorkManager.getInstance(context)
        val favourites = FavouriteTrainStore(context).all()
        val store = PunctualityRecorderStore(context)
        val now = Instant.now()
        val next = if (LiveNotificationSettings(context).punctualityRecordingEnabled) {
            PunctualityRecordingPlanner.nextRun(favourites.map(store::candidate), now)
        } else null
        if (next == null) {
            if (!fromWorker) workManager.cancelUniqueWork(WORK)
            return
        }
        val minimum = if (fromWorker) Duration.ofMinutes(1) else Duration.ZERO
        val delay = Duration.between(now, next).let { if (it < minimum) minimum else it }
        val request = OneTimeWorkRequestBuilder<PunctualityRecorderWorker>()
            .setConstraints(networkConnected)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .addTag(WORK)
            .build()
        workManager.enqueueUniqueWork(WORK, if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
    }
}

/** Records the final delay (and the per-stop delays) of the favourite trains that are due; nothing is notified. */
class PunctualityRecorderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        try {
            if (LiveNotificationSettings(context).punctualityRecordingEnabled) record(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The attempt is counted; the next run retries within the daily limit.
        } finally {
            if (!isStopped) PunctualityRecorderScheduler.schedule(context, fromWorker = true)
        }
        Result.success()
    }

    private suspend fun record(context: Context) {
        val favourites = FavouriteTrainStore(context).all()
        val store = PunctualityRecorderStore(context)
        store.retainOnly(favourites.map { it.number })
        val now = Instant.now()
        val zone = RailTime.defaultZone
        val due = PunctualityRecordingPlanner.due(favourites.map(store::candidate), now, zone)
        if (due.isEmpty()) return
        val search = BinarioDataGraph.trainNumberSearchService(context)
        val history = PunctualityHistoryStore(context)
        due.forEach { (candidate, date) ->
            val monitor = favourites.first { PunctualityHistory.trainKey(it.number) == PunctualityHistory.trainKey(candidate.number) }
            val attempted = candidate.copy(
                attempts = (if (candidate.attemptDate == date) candidate.attempts else 0) + 1,
                attemptDate = date,
                lastAttempt = now
            )
            store.save(attempted)
            val update = try {
                withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) { search.search(TrainNumberQuery(monitor.number, date)).lastOrNull() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val service = update?.let { FavouriteTrainMatcher.pick(it.matches.map { match -> match.service }, monitor) }
            if (service == null) {
                // A day the train does not run is done after one lookup; only a failed or ambiguous answer is retried.
                if (PunctualityRecordingPlanner.runsNot(update)) store.save(attempted.copy(recordedOn = date))
                return@forEach
            }
            val departure = candidate.departure ?: runCatching { LocalTime.parse(service.scheduledDeparture.trim()) }.getOrNull()
            val arrival = service.stops.lastOrNull()?.let { ConnectionRiskClassifier.arrival(service, it, date, zone)?.scheduled }
            val learned = if (departure != null && arrival != null) {
                Duration.between(LocalDateTime.of(date, departure).atZone(zone).toInstant(), arrival).toMinutes().toInt().takeIf { it > 0 }
            } else null
            val updated = attempted.copy(durationMinutes = learned ?: attempted.durationMinutes)
            if (arrival == null || !service.operationalStatusVerified || !PunctualityRecordingPlanner.arrived(service, arrival, now)) {
                // Not arrived yet (or no live data): the learned arrival moves the next attempt after it, within the same
                // daily limit of attempts.
                store.save(updated)
                return@forEach
            }
            PunctualityRecordingPlanner.observation(service, monitor.number, date)?.let { observation ->
                history.record(history.read(), listOf(observation))
            }
            store.save(updated.copy(recordedOn = date))
        }
    }

    private companion object {
        const val LOOKUP_TIMEOUT_MILLIS = 45_000L
    }
}
