// Harness compile stubs for androidx.annotation (annotation-jvm is Google-Maven-only; JetBrains' annotation-internal
// 1.7.x jar is KMP metadata only). Pure marker annotations, so a stub is semantically identical.
@file:Suppress("unused", "PackageDirectoryMismatch")

package androidx.annotation

import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.annotation.AnnotationRetention.SOURCE

@Retention(SOURCE) annotation class StringRes
@Retention(SOURCE) annotation class PluralsRes
@Retention(SOURCE) annotation class DrawableRes
@Retention(SOURCE) annotation class ColorRes
@Retention(SOURCE) annotation class ColorInt
@Retention(SOURCE) annotation class LayoutRes
@Retention(SOURCE) annotation class IdRes
@Retention(SOURCE) annotation class XmlRes
@Retention(SOURCE) annotation class RawRes
@Retention(SOURCE) annotation class AttrRes
@Retention(SOURCE) annotation class StyleRes
@Retention(SOURCE) annotation class DimenRes
@Retention(SOURCE) annotation class ArrayRes
@Retention(SOURCE) annotation class IntegerRes
@Retention(SOURCE) annotation class BoolRes
@Retention(SOURCE) annotation class AnyRes
@Retention(SOURCE) annotation class Px
@Retention(SOURCE) annotation class Dimension(val unit: Int = 0)
@Retention(BINARY) annotation class IntRange(val from: Long = Long.MIN_VALUE, val to: Long = Long.MAX_VALUE)
@Retention(BINARY) annotation class FloatRange(val from: Double = Double.NEGATIVE_INFINITY, val to: Double = Double.POSITIVE_INFINITY,
                                               val fromInclusive: Boolean = true, val toInclusive: Boolean = true)
@Retention(BINARY) annotation class Size(val value: Long = -1, val min: Long = Long.MIN_VALUE, val max: Long = Long.MAX_VALUE, val multiple: Long = 1)
@Retention(BINARY) annotation class RequiresApi(val value: Int = 1, val api: Int = 1)
@Retention(BINARY) annotation class RequiresPermission(val value: String = "", val allOf: Array<String> = [], val anyOf: Array<String> = [], val conditional: Boolean = false)
@Retention(BINARY) annotation class CallSuper
@Retention(BINARY) annotation class CheckResult(val suggest: String = "")
@Retention(BINARY) annotation class Keep
@Retention(BINARY) annotation class MainThread
@Retention(BINARY) annotation class UiThread
@Retention(BINARY) annotation class WorkerThread
@Retention(BINARY) annotation class AnyThread
@Retention(BINARY) annotation class VisibleForTesting(val otherwise: Int = 2)
@Retention(BINARY) annotation class RestrictTo(vararg val value: Scope) {
    enum class Scope { LIBRARY, LIBRARY_GROUP, LIBRARY_GROUP_PREFIX, GROUP_ID, TESTS, SUBCLASSES }
}
@Retention(BINARY) annotation class Discouraged(val message: String = "")
@Retention(BINARY) annotation class DoNotInline
@Retention(BINARY) annotation class ChecksSdkIntAtLeast(val api: Int = -1, val codename: String = "", val parameter: Int = -1, val lambda: Int = -1, val extension: Int = 0)
