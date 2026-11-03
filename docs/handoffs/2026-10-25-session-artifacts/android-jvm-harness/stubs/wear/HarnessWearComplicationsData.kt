// Harness compile stubs for androidx.wear.watchface.complications.data 1.3.0 (Google-Maven-only, unreachable here).
@file:Suppress("unused", "PackageDirectoryMismatch", "MemberVisibilityCanBePrivate")

package androidx.wear.watchface.complications.data

import java.time.Instant

enum class ComplicationType {
    NO_DATA, EMPTY, NOT_CONFIGURED, SHORT_TEXT, LONG_TEXT, RANGED_VALUE, MONOCHROMATIC_IMAGE, SMALL_IMAGE,
    PHOTO_IMAGE, NO_PERMISSION, GOAL_PROGRESS, WEIGHTED_ELEMENTS,
}

interface ComplicationText {
    val text: CharSequence
}

class PlainComplicationText internal constructor(override val text: CharSequence) : ComplicationText {
    class Builder(private val text: CharSequence) {
        fun build(): PlainComplicationText = PlainComplicationText(text)
    }
    override fun toString(): String = "PlainComplicationText(text=$text)"
}

/** Validity window of a complication value; the watch face hides data outside it. */
class TimeRange internal constructor(val startDateTimeMillis: Long, val endDateTimeMillis: Long) {
    operator fun contains(dateTime: Instant): Boolean = dateTime.toEpochMilli() in startDateTimeMillis..endDateTimeMillis

    companion object {
        @JvmField val ALWAYS: TimeRange = TimeRange(0L, Long.MAX_VALUE)
        @JvmStatic fun between(startInstant: Instant, endInstant: Instant): TimeRange = TimeRange(startInstant.toEpochMilli(), endInstant.toEpochMilli())
        @JvmStatic fun before(endInstant: Instant): TimeRange = TimeRange(0L, endInstant.toEpochMilli())
        @JvmStatic fun after(startInstant: Instant): TimeRange = TimeRange(startInstant.toEpochMilli(), Long.MAX_VALUE)
    }
}

abstract class ComplicationData internal constructor(val type: ComplicationType, val validTimeRange: TimeRange = TimeRange.ALWAYS) {
    open val contentDescription: ComplicationText? get() = null
}

class NoDataComplicationData : ComplicationData(ComplicationType.NO_DATA)

class EmptyComplicationData : ComplicationData(ComplicationType.EMPTY)

class ShortTextComplicationData internal constructor(
    val text: ComplicationText,
    override val contentDescription: ComplicationText?,
    val title: ComplicationText?,
    validTimeRange: TimeRange?,
) : ComplicationData(ComplicationType.SHORT_TEXT, validTimeRange ?: TimeRange.ALWAYS) {
    class Builder(private val text: ComplicationText, private val contentDescription: ComplicationText) {
        private var title: ComplicationText? = null
        private var validTimeRange: TimeRange? = null
        fun setTitle(title: ComplicationText?): Builder { this.title = title; return this }
        fun setValidTimeRange(validTimeRange: TimeRange?): Builder { this.validTimeRange = validTimeRange; return this }
        fun build(): ShortTextComplicationData = ShortTextComplicationData(text, contentDescription, title, validTimeRange)
    }
}

class LongTextComplicationData internal constructor(
    val text: ComplicationText,
    override val contentDescription: ComplicationText?,
    val title: ComplicationText?,
    validTimeRange: TimeRange?,
) : ComplicationData(ComplicationType.LONG_TEXT, validTimeRange ?: TimeRange.ALWAYS) {
    class Builder(private val text: ComplicationText, private val contentDescription: ComplicationText) {
        private var title: ComplicationText? = null
        private var validTimeRange: TimeRange? = null
        fun setTitle(title: ComplicationText?): Builder { this.title = title; return this }
        fun setValidTimeRange(validTimeRange: TimeRange?): Builder { this.validTimeRange = validTimeRange; return this }
        fun build(): LongTextComplicationData = LongTextComplicationData(text, contentDescription, title, validTimeRange)
    }
}
