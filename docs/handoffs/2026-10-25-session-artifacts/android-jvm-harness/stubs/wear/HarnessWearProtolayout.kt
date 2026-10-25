// Harness compile stubs for androidx.wear.protolayout 1.4.2 (Google-Maven-only, unreachable here).
// Builders and getters keep the real shapes (StringProp/Int32Prop/DpProp wrappers, int alignment constants);
// Layout.toByteArray() is a deterministic UTF-8 structural dump instead of the protobuf wire format (the Wear
// tile test only compares two serialisations of the same layout for equality).
@file:Suppress("unused", "PackageDirectoryMismatch", "MemberVisibilityCanBePrivate")

package androidx.wear.protolayout

object TypeBuilders {
    class StringProp internal constructor(val value: String) {
        class Builder(private val value: String) {
            fun build(): StringProp = StringProp(value)
        }
        override fun toString(): String = "StringProp(value=$value)"
    }

    class Int32Prop internal constructor(val value: Int) {
        class Builder(private val value: Int) {
            fun build(): Int32Prop = Int32Prop(value)
        }
        override fun toString(): String = "Int32Prop(value=$value)"
    }
}

object ColorBuilders {
    class ColorProp internal constructor(val argb: Int) {
        class Builder(private val argb: Int) {
            fun build(): ColorProp = ColorProp(argb)
        }
        override fun toString(): String = "ColorProp(argb=$argb)"
    }
}

object DimensionBuilders {
    /** Width/height of a container: a DpProp, expand() or wrap(). */
    interface ContainerDimension {
        fun describe(): String
    }

    class SpProp internal constructor(val value: Float) {
        class Builder {
            private var value = 0f
            fun setValue(value: Float): Builder { this.value = value; return this }
            fun build(): SpProp = SpProp(value)
        }
        override fun toString(): String = "SpProp(value=$value)"
    }

    class DpProp internal constructor(val value: Float) : ContainerDimension {
        class Builder {
            private var value = 0f
            fun setValue(value: Float): Builder { this.value = value; return this }
            fun build(): DpProp = DpProp(value)
        }
        override fun describe(): String = toString()
        override fun toString(): String = "DpProp(value=$value)"
    }

    class ExpandedDimensionProp internal constructor() : ContainerDimension {
        override fun describe(): String = "expand"
    }

    class WrappedDimensionProp internal constructor() : ContainerDimension {
        override fun describe(): String = "wrap"
    }

    @JvmStatic fun dp(value: Float): DpProp = DpProp.Builder().setValue(value).build()
    @JvmStatic fun sp(value: Float): SpProp = SpProp.Builder().setValue(value).build()
    @JvmStatic fun expand(): ExpandedDimensionProp = ExpandedDimensionProp()
    @JvmStatic fun wrap(): WrappedDimensionProp = WrappedDimensionProp()
}

object DeviceParametersBuilders {
    const val SCREEN_SHAPE_UNDEFINED = 0
    const val SCREEN_SHAPE_ROUND = 1
    const val SCREEN_SHAPE_RECT = 2

    class DeviceParameters internal constructor(
        val screenWidthDp: Int,
        val screenHeightDp: Int,
        val screenDensity: Float,
        val screenShape: Int,
        val fontScale: Float,
    ) {
        class Builder {
            private var screenWidthDp = 0
            private var screenHeightDp = 0
            private var screenDensity = 0f
            private var screenShape = SCREEN_SHAPE_UNDEFINED
            private var fontScale = 1f
            fun setScreenWidthDp(screenWidthDp: Int): Builder { this.screenWidthDp = screenWidthDp; return this }
            fun setScreenHeightDp(screenHeightDp: Int): Builder { this.screenHeightDp = screenHeightDp; return this }
            fun setScreenDensity(screenDensity: Float): Builder { this.screenDensity = screenDensity; return this }
            fun setScreenShape(screenShape: Int): Builder { this.screenShape = screenShape; return this }
            fun setFontScale(fontScale: Float): Builder { this.fontScale = fontScale; return this }
            fun build(): DeviceParameters = DeviceParameters(screenWidthDp, screenHeightDp, screenDensity, screenShape, fontScale)
        }
    }
}

object ModifiersBuilders {
    class Padding internal constructor(val start: DimensionBuilders.DpProp?, val end: DimensionBuilders.DpProp?, val top: DimensionBuilders.DpProp?, val bottom: DimensionBuilders.DpProp?) {
        class Builder {
            private var start: DimensionBuilders.DpProp? = null
            private var end: DimensionBuilders.DpProp? = null
            private var top: DimensionBuilders.DpProp? = null
            private var bottom: DimensionBuilders.DpProp? = null
            fun setStart(start: DimensionBuilders.DpProp): Builder { this.start = start; return this }
            fun setEnd(end: DimensionBuilders.DpProp): Builder { this.end = end; return this }
            fun setTop(top: DimensionBuilders.DpProp): Builder { this.top = top; return this }
            fun setBottom(bottom: DimensionBuilders.DpProp): Builder { this.bottom = bottom; return this }
            fun setAll(all: DimensionBuilders.DpProp): Builder { start = all; end = all; top = all; bottom = all; return this }
            fun build(): Padding = Padding(start, end, top, bottom)
        }
        override fun toString(): String = "Padding(start=$start, end=$end, top=$top, bottom=$bottom)"
    }

    class Modifiers internal constructor(val padding: Padding?) {
        class Builder {
            private var padding: Padding? = null
            fun setPadding(padding: Padding): Builder { this.padding = padding; return this }
            fun build(): Modifiers = Modifiers(padding)
        }
        override fun toString(): String = "Modifiers(padding=$padding)"
    }
}

object LayoutElementBuilders {
    const val HORIZONTAL_ALIGN_UNDEFINED = 0
    const val HORIZONTAL_ALIGN_LEFT = 1
    const val HORIZONTAL_ALIGN_CENTER = 2
    const val HORIZONTAL_ALIGN_RIGHT = 3
    const val HORIZONTAL_ALIGN_START = 4
    const val HORIZONTAL_ALIGN_END = 5
    const val VERTICAL_ALIGN_UNDEFINED = 0
    const val VERTICAL_ALIGN_TOP = 1
    const val VERTICAL_ALIGN_CENTER = 2
    const val VERTICAL_ALIGN_BOTTOM = 3
    const val TEXT_ALIGN_UNDEFINED = 0
    const val TEXT_ALIGN_START = 1
    const val TEXT_ALIGN_CENTER = 2
    const val TEXT_ALIGN_END = 3

    interface LayoutElement {
        /** Structural description used by [Layout.toByteArray]. */
        fun describe(): String
    }

    class HorizontalAlignmentProp internal constructor(val value: Int) {
        override fun toString(): String = "HorizontalAlignmentProp(value=$value)"
    }

    class VerticalAlignmentProp internal constructor(val value: Int) {
        override fun toString(): String = "VerticalAlignmentProp(value=$value)"
    }

    class TextAlignmentProp internal constructor(val value: Int) {
        override fun toString(): String = "TextAlignmentProp(value=$value)"
    }

    class FontStyle internal constructor(val size: DimensionBuilders.SpProp?, val color: ColorBuilders.ColorProp?) {
        class Builder {
            private var size: DimensionBuilders.SpProp? = null
            private var color: ColorBuilders.ColorProp? = null
            fun setSize(size: DimensionBuilders.SpProp): Builder { this.size = size; return this }
            fun setColor(color: ColorBuilders.ColorProp): Builder { this.color = color; return this }
            fun build(): FontStyle = FontStyle(size, color)
        }
        override fun toString(): String = "FontStyle(size=$size, color=$color)"
    }

    class Text internal constructor(
        val text: TypeBuilders.StringProp?,
        val maxLines: TypeBuilders.Int32Prop?,
        val fontStyle: FontStyle?,
        val multilineAlignment: TextAlignmentProp?,
    ) : LayoutElement {
        class Builder {
            private var text: TypeBuilders.StringProp? = null
            private var maxLines: TypeBuilders.Int32Prop? = null
            private var fontStyle: FontStyle? = null
            private var multilineAlignment: TextAlignmentProp? = null
            fun setText(text: String): Builder { this.text = TypeBuilders.StringProp.Builder(text).build(); return this }
            fun setText(text: TypeBuilders.StringProp): Builder { this.text = text; return this }
            fun setMaxLines(maxLines: Int): Builder { this.maxLines = TypeBuilders.Int32Prop.Builder(maxLines).build(); return this }
            fun setFontStyle(fontStyle: FontStyle): Builder { this.fontStyle = fontStyle; return this }
            fun setMultilineAlignment(multilineAlignment: Int): Builder { this.multilineAlignment = TextAlignmentProp(multilineAlignment); return this }
            fun build(): Text = Text(text, maxLines, fontStyle, multilineAlignment)
        }
        override fun describe(): String = "Text(text=$text, maxLines=$maxLines, fontStyle=$fontStyle, multilineAlignment=$multilineAlignment)"
    }

    class Column internal constructor(
        val contents: List<LayoutElement>,
        val width: DimensionBuilders.ContainerDimension?,
        val height: DimensionBuilders.ContainerDimension?,
        val horizontalAlignment: HorizontalAlignmentProp?,
        val modifiers: ModifiersBuilders.Modifiers?,
    ) : LayoutElement {
        class Builder {
            private val contents = mutableListOf<LayoutElement>()
            private var width: DimensionBuilders.ContainerDimension? = null
            private var height: DimensionBuilders.ContainerDimension? = null
            private var horizontalAlignment: HorizontalAlignmentProp? = null
            private var modifiers: ModifiersBuilders.Modifiers? = null
            fun addContent(content: LayoutElement): Builder { contents += content; return this }
            fun setWidth(width: DimensionBuilders.ContainerDimension): Builder { this.width = width; return this }
            fun setHeight(height: DimensionBuilders.ContainerDimension): Builder { this.height = height; return this }
            fun setHorizontalAlignment(horizontalAlignment: Int): Builder { this.horizontalAlignment = HorizontalAlignmentProp(horizontalAlignment); return this }
            fun setModifiers(modifiers: ModifiersBuilders.Modifiers): Builder { this.modifiers = modifiers; return this }
            fun build(): Column = Column(contents.toList(), width, height, horizontalAlignment, modifiers)
        }
        override fun describe(): String =
            "Column(width=${width?.describe()}, height=${height?.describe()}, horizontalAlignment=$horizontalAlignment, modifiers=$modifiers, [${contents.joinToString(",") { it.describe() }}])"
    }

    class Row internal constructor(val contents: List<LayoutElement>) : LayoutElement {
        class Builder {
            private val contents = mutableListOf<LayoutElement>()
            fun addContent(content: LayoutElement): Builder { contents += content; return this }
            fun build(): Row = Row(contents.toList())
        }
        override fun describe(): String = "Row(${contents.joinToString(",") { it.describe() }})"
    }

    class Box internal constructor(
        val contents: List<LayoutElement>,
        val width: DimensionBuilders.ContainerDimension?,
        val height: DimensionBuilders.ContainerDimension?,
        val horizontalAlignment: HorizontalAlignmentProp?,
        val verticalAlignment: VerticalAlignmentProp?,
        val modifiers: ModifiersBuilders.Modifiers?,
    ) : LayoutElement {
        class Builder {
            private val contents = mutableListOf<LayoutElement>()
            private var width: DimensionBuilders.ContainerDimension? = null
            private var height: DimensionBuilders.ContainerDimension? = null
            private var horizontalAlignment: HorizontalAlignmentProp? = null
            private var verticalAlignment: VerticalAlignmentProp? = null
            private var modifiers: ModifiersBuilders.Modifiers? = null
            fun addContent(content: LayoutElement): Builder { contents += content; return this }
            fun setWidth(width: DimensionBuilders.ContainerDimension): Builder { this.width = width; return this }
            fun setHeight(height: DimensionBuilders.ContainerDimension): Builder { this.height = height; return this }
            fun setHorizontalAlignment(horizontalAlignment: Int): Builder { this.horizontalAlignment = HorizontalAlignmentProp(horizontalAlignment); return this }
            fun setVerticalAlignment(verticalAlignment: Int): Builder { this.verticalAlignment = VerticalAlignmentProp(verticalAlignment); return this }
            fun setModifiers(modifiers: ModifiersBuilders.Modifiers): Builder { this.modifiers = modifiers; return this }
            fun build(): Box = Box(contents.toList(), width, height, horizontalAlignment, verticalAlignment, modifiers)
        }
        override fun describe(): String =
            "Box(width=${width?.describe()}, height=${height?.describe()}, horizontalAlignment=$horizontalAlignment, verticalAlignment=$verticalAlignment, modifiers=$modifiers, [${contents.joinToString(",") { it.describe() }}])"
    }

    class Layout internal constructor(val root: LayoutElement?) {
        class Builder {
            private var root: LayoutElement? = null
            fun setRoot(root: LayoutElement): Builder { this.root = root; return this }
            fun build(): Layout = Layout(root)
        }
        fun toByteArray(): ByteArray = ("Layout(" + (root?.describe() ?: "") + ")").toByteArray(Charsets.UTF_8)

        companion object {
            @JvmStatic fun fromLayoutElement(layoutElement: LayoutElement): Layout = Layout(layoutElement)
            @JvmStatic fun fromByteArray(bytes: ByteArray): Layout? = null
        }
    }
}

object ResourceBuilders {
    class Resources internal constructor(val version: String) {
        class Builder {
            private var version = ""
            fun setVersion(version: String): Builder { this.version = version; return this }
            fun build(): Resources = Resources(version)
        }
    }
}

object TimelineBuilders {
    class TimeInterval internal constructor(val startMillis: Long, val endMillis: Long) {
        class Builder {
            private var startMillis = 0L
            private var endMillis = 0L
            fun setStartMillis(startMillis: Long): Builder { this.startMillis = startMillis; return this }
            fun setEndMillis(endMillis: Long): Builder { this.endMillis = endMillis; return this }
            fun build(): TimeInterval = TimeInterval(startMillis, endMillis)
        }
    }

    class TimelineEntry internal constructor(val layout: LayoutElementBuilders.Layout?, val validity: TimeInterval?) {
        class Builder {
            private var layout: LayoutElementBuilders.Layout? = null
            private var validity: TimeInterval? = null
            fun setLayout(layout: LayoutElementBuilders.Layout): Builder { this.layout = layout; return this }
            fun setValidity(validity: TimeInterval): Builder { this.validity = validity; return this }
            fun build(): TimelineEntry = TimelineEntry(layout, validity)
        }

        companion object {
            @JvmStatic fun fromLayoutElement(layoutElement: LayoutElementBuilders.LayoutElement): TimelineEntry =
                TimelineEntry(LayoutElementBuilders.Layout.fromLayoutElement(layoutElement), null)
        }
    }

    class Timeline internal constructor(val timelineEntries: List<TimelineEntry>) {
        class Builder {
            private val entries = mutableListOf<TimelineEntry>()
            fun addTimelineEntry(timelineEntry: TimelineEntry): Builder { entries += timelineEntry; return this }
            fun build(): Timeline = Timeline(entries.toList())
        }

        companion object {
            @JvmStatic fun fromLayoutElement(layoutElement: LayoutElementBuilders.LayoutElement): Timeline =
                Timeline(listOf(TimelineEntry.fromLayoutElement(layoutElement)))
        }
    }
}
