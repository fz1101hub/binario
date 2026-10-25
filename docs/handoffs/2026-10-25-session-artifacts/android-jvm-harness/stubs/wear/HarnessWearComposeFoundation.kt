// Harness compile stubs for androidx.wear.compose.foundation.lazy 1.6.2 (Google-Maven-only, unreachable here).
// ScalingLazyColumn delegates to the JetBrains Compose LazyColumn; scaling, auto-centering and rotary input are
// not simulated because no composable runs on the JVM. The parameters kept here have the real names, types,
// order and defaults; flingBehavior, scalingParams, anchorType and rotaryScrollableBehavior are omitted.
@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package androidx.wear.compose.foundation.lazy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Stable
class ScalingLazyListState(initialCenterItemIndex: Int = 1, initialCenterItemScrollOffset: Int = 0) {
    var centerItemIndex: Int = initialCenterItemIndex
        private set
    var centerItemScrollOffset: Int = initialCenterItemScrollOffset
        private set
}

@Composable
fun rememberScalingLazyListState(initialCenterItemIndex: Int = 1, initialCenterItemScrollOffset: Int = 0): ScalingLazyListState =
    remember { ScalingLazyListState(initialCenterItemIndex, initialCenterItemScrollOffset) }

class AutoCenteringParams(val itemIndex: Int = 1, val itemOffset: Int = 0)

interface ScalingLazyListItemScope {
    fun Modifier.fillParentMaxSize(fraction: Float = 1f): Modifier
    fun Modifier.fillParentMaxWidth(fraction: Float = 1f): Modifier
    fun Modifier.fillParentMaxHeight(fraction: Float = 1f): Modifier
}

interface ScalingLazyListScope {
    fun item(key: Any? = null, contentType: Any? = null, content: @Composable ScalingLazyListItemScope.() -> Unit)
    fun items(
        count: Int,
        key: ((index: Int) -> Any)? = null,
        contentType: (index: Int) -> Any? = { null },
        itemContent: @Composable ScalingLazyListItemScope.(index: Int) -> Unit,
    )
}

inline fun <T> ScalingLazyListScope.items(
    items: List<T>,
    noinline key: ((item: T) -> Any)? = null,
    noinline contentType: (item: T) -> Any? = { null },
    crossinline itemContent: @Composable ScalingLazyListItemScope.(item: T) -> Unit,
) = items(items.size, if (key != null) { index: Int -> key(items[index]) } else null, { index: Int -> contentType(items[index]) }) {
    itemContent(items[it])
}

inline fun <T> ScalingLazyListScope.itemsIndexed(
    items: List<T>,
    noinline key: ((index: Int, item: T) -> Any)? = null,
    noinline contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
    crossinline itemContent: @Composable ScalingLazyListItemScope.(index: Int, item: T) -> Unit,
) = items(items.size, if (key != null) { index: Int -> key(index, items[index]) } else null, { index: Int -> contentType(index, items[index]) }) {
    itemContent(it, items[it])
}

private object HarnessScalingLazyListItemScope : ScalingLazyListItemScope {
    override fun Modifier.fillParentMaxSize(fraction: Float): Modifier = fillMaxSize(fraction)
    override fun Modifier.fillParentMaxWidth(fraction: Float): Modifier = fillMaxWidth(fraction)
    override fun Modifier.fillParentMaxHeight(fraction: Float): Modifier = fillMaxHeight(fraction)
}

private class HarnessScalingLazyListScope(private val delegate: LazyListScope) : ScalingLazyListScope {
    override fun item(key: Any?, contentType: Any?, content: @Composable ScalingLazyListItemScope.() -> Unit) {
        delegate.item(key, contentType) { HarnessScalingLazyListItemScope.content() }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable ScalingLazyListItemScope.(index: Int) -> Unit,
    ) {
        delegate.items(count, key, contentType) { HarnessScalingLazyListItemScope.itemContent(it) }
    }
}

@Composable
fun ScalingLazyColumn(
    modifier: Modifier = Modifier,
    state: ScalingLazyListState = rememberScalingLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        Arrangement.spacedBy(space = 4.dp, alignment = if (!reverseLayout) Alignment.Top else Alignment.Bottom),
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    userScrollEnabled: Boolean = true,
    autoCentering: AutoCenteringParams? = AutoCenteringParams(),
    content: ScalingLazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        userScrollEnabled = userScrollEnabled,
    ) { HarnessScalingLazyListScope(this).content() }
}
