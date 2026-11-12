package it.binario.app.data.gtfs

import it.binario.app.data.repository.TrainNumberQuery
import java.time.LocalDate

/**
 * The train numbers one pack publishes, kept as sorted 64-bit hashes (a few hundred kilobytes for the largest pack):
 * enough to say that a pack does not have a number without opening it. [mayContain] is true for every number
 * [TrainNumberQuery.matches] would accept on some trip of the pack; a hash collision can only make it true once too
 * often, and the pack is then read as before.
 */
internal class GtfsPackNumbers private constructor(private val exact: LongArray, private val numeric: LongArray) {
    fun mayContain(query: TrainNumberQuery): Boolean =
        exact.binarySearch(hash(query.normalized)) >= 0 ||
            query.numeric?.let { numeric.binarySearch(hash(it)) >= 0 } == true

    /** Collects the published numbers of a pack while its trips are read. */
    class Builder {
        private val published = HashSet<String>()

        fun add(number: String) {
            published += number
        }

        /**
         * The three forms [TrainNumberQuery.matches] compares a query with: the whole published number, the part after
         * " - " ("RE_11 - 2153" is 2153), and that part's digits when it is a category and a number ("REG 2581").
         */
        fun build(date: LocalDate): GtfsPackNumbers {
            val exact = HashSet<Long>(published.size * 2)
            val numeric = HashSet<Long>(published.size * 2)
            for (number in published) {
                exact += hash(TrainNumberQuery(number, date).normalized)
                val candidate = TrainNumberQuery(number.substringAfterLast(" - ").trim(), date)
                exact += hash(candidate.normalized)
                candidate.numeric?.let { numeric += hash(it) }
            }
            return GtfsPackNumbers(exact.toLongArray().also(LongArray::sort), numeric.toLongArray().also(LongArray::sort))
        }
    }

    private companion object {
        /** FNV-1a over the UTF-16 units. */
        fun hash(value: String): Long {
            var hash = -0x340d631b7bdddcdbL
            for (character in value) {
                hash = hash xor character.code.toLong()
                hash *= 0x100000001b3L
            }
            return hash
        }
    }
}
