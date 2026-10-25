// Harness compile stubs: Android-only resource helpers of androidx.compose.ui.res (stringResource, pluralStringResource).
@file:Suppress("unused", "PackageDirectoryMismatch")

package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources

@Composable
@ReadOnlyComposable
fun stringResource(id: Int): String = LocalResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun stringResource(id: Int, vararg formatArgs: Any): String = LocalResources.current.getString(id, *formatArgs)

@Composable
@ReadOnlyComposable
fun stringArrayResource(id: Int): Array<String> = LocalResources.current.getStringArray(id)

@Composable
@ReadOnlyComposable
fun pluralStringResource(id: Int, count: Int): String = LocalResources.current.getQuantityString(id, count)

@Composable
@ReadOnlyComposable
fun pluralStringResource(id: Int, count: Int, vararg formatArgs: Any): String =
    LocalResources.current.getQuantityString(id, count, *formatArgs)
