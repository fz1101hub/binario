package it.binario.app.data.provider

import it.binario.app.data.TrainCategory
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.ServiceKind
import java.util.Locale

/** Walking speed of the walks of a journey (to the metro, between two stations). */
enum class WalkingSpeed(val kmh: Double) {
    SLOW(3.5),
    NORMAL(4.5),
    FAST(5.5)
}

/** "Preferisci": how the results are ranked. It never changes which journeys are correct. */
enum class JourneyRanking {
    /** The journeys that arrive first, in order of departure (the planner's own order): the default. */
    FASTEST,
    /** Fewest changes first. */
    FEWEST_CHANGES,
    /** Least walking first. */
    LEAST_WALKING
}

/**
 * The kinds of service a traveller can prefer or avoid. A leg of a kind the source does not tell (a foreign train
 * without category) has no [TravelMode] and is never avoided.
 */
enum class TravelMode {
    /** Frecce and the other high-speed trains (Railjet, TGV, ICE), Italo excluded. */
    HIGH_SPEED,
    /** Intercity, EuroCity and night trains. */
    INTERCITY,
    /** Regional and suburban trains. */
    REGIONAL,
    /** Italo trains (ItaloBus is [OPERATOR_BUS]). */
    ITALO,
    METRO,
    TRAM,
    FUNICULAR,
    /** The operators' own coaches (Trenitalia, FrecciaLink, ItaloBus) and rail replacement buses. */
    OPERATOR_BUS;

    companion object {
        /** The kind of a journey leg; null for a walk or a train of unknown kind. */
        fun of(mode: LegMode, serviceKind: ServiceKind, operatorName: String?): TravelMode? = when (mode) {
            LegMode.BUS, LegMode.REPLACEMENT_BUS -> OPERATOR_BUS
            LegMode.METRO -> METRO
            LegMode.TRAM -> TRAM
            LegMode.FUNICULAR -> FUNICULAR
            LegMode.WALK, LegMode.UNKNOWN -> null
            LegMode.TRAIN -> when {
                operatorName.orEmpty().lowercase(Locale.ROOT).contains("italo") -> ITALO
                serviceKind == ServiceKind.HIGH_SPEED -> HIGH_SPEED
                serviceKind == ServiceKind.INTERCITY || serviceKind == ServiceKind.NIGHT -> INTERCITY
                serviceKind == ServiceKind.REGIONAL || serviceKind == ServiceKind.SUBURBAN -> REGIONAL
                else -> null
            }
        }

        /** The kind of a direct train of the route search; null when its category is unknown ("Altri"). */
        fun ofTrain(category: TrainCategory, scheduledBus: Boolean): TravelMode? = when {
            scheduledBus -> OPERATOR_BUS
            else -> when (category) {
                TrainCategory.FRECCE -> HIGH_SPEED
                TrainCategory.AV_ITALO -> ITALO
                TrainCategory.INTERCITY -> INTERCITY
                TrainCategory.REGIONALE, TrainCategory.REGIONALE_VELOCE, TrainCategory.TRENORD_REGIO,
                TrainCategory.METROPOLITANO -> REGIONAL
                else -> null
            }
        }
    }
}

/**
 * "Preferenze di viaggio", kept on the device and sent with every journey search. The walking choices change the
 * walks the planner offers (minutes = distance / [walkingSpeed] + 2, at most [maxWalkMinutes] per walk); [ranking]
 * and [preferredModes] only reorder; [avoidedModes] and [wheelchairOnly] leave journeys out.
 */
data class JourneyPreferences(
    val walkingSpeed: WalkingSpeed = WalkingSpeed.NORMAL,
    /** Longest single walk, margin included: one of [MAX_WALK_CHOICES]. */
    val maxWalkMinutes: Int = DEFAULT_MAX_WALK_MINUTES,
    val ranking: JourneyRanking = JourneyRanking.FASTEST,
    val preferredModes: Set<TravelMode> = emptySet(),
    val avoidedModes: Set<TravelMode> = emptySet(),
    /**
     * "Solo percorsi accessibili in sedia a rotelle": every trip and every boarding and alighting stop must be marked
     * accessible (GTFS 1) in the timetable, and a walk between stations needs both ends marked; unknown never counts.
     */
    val wheelchairOnly: Boolean = false
) {
    init {
        require(maxWalkMinutes in MAX_WALK_CHOICES) { "Tempo a piedi non supportato" }
        require(preferredModes.none { it in avoidedModes }) { "Un tipo di servizio non può essere preferito ed evitato" }
    }

    /** A copy where [mode] is preferred, avoided or neither (exactly one of the three). */
    fun withMode(mode: TravelMode, choice: ModeChoice): JourneyPreferences = copy(
        preferredModes = if (choice == ModeChoice.PREFER) preferredModes + mode else preferredModes - mode,
        avoidedModes = if (choice == ModeChoice.AVOID) avoidedModes + mode else avoidedModes - mode
    )

    fun choiceOf(mode: TravelMode): ModeChoice = when (mode) {
        in preferredModes -> ModeChoice.PREFER
        in avoidedModes -> ModeChoice.AVOID
        else -> ModeChoice.ANY
    }

    /** Something differs from the defaults: the results say that the travel preferences apply. */
    val isCustomized: Boolean get() = this != JourneyPreferences()

    companion object {
        const val DEFAULT_MAX_WALK_MINUTES = 10
        val MAX_WALK_CHOICES = listOf(5, 10, 15, 20)
    }
}

/** One service kind in the travel preferences. */
enum class ModeChoice { ANY, PREFER, AVOID }

/**
 * A journey provider that knows whether its timetables carry wheelchair data around the two ends of a search, so that
 * an empty "accessible only" search can say "Dati di accessibilità non disponibili per questa zona".
 */
interface WheelchairDataAware {
    suspend fun hasWheelchairData(request: JourneySearchRequest): Boolean
}
