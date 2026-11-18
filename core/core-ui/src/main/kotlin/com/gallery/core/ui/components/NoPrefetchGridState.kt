package com.gallery.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridPrefetchScope
import androidx.compose.foundation.lazy.grid.LazyGridPrefetchStrategy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * A [LazyGridPrefetchStrategy] that disables both off-screen row prefetching during scroll
 * and nested prefetching, ensuring items outside the active screen viewport are never
 * pre-composed, pre-measured, or decoded before entering view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Stable
object NoOpLazyGridPrefetchStrategy : LazyGridPrefetchStrategy {
    override fun LazyGridPrefetchScope.onScroll(delta: Float, layoutInfo: LazyGridLayoutInfo) = Unit
    override fun LazyGridPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyGridLayoutInfo) = Unit
    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) = Unit
}

@OptIn(ExperimentalFoundationApi::class)
fun createViewportOnlyLazyGridState(
    firstVisibleItemIndex: Int = 0,
    firstVisibleItemScrollOffset: Int = 0
): LazyGridState = LazyGridState(
    firstVisibleItemIndex = firstVisibleItemIndex,
    firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
    prefetchStrategy = NoOpLazyGridPrefetchStrategy
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberViewportOnlyLazyGridState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0
): LazyGridState = rememberLazyGridState(
    initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
    initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
    prefetchStrategy = remember { NoOpLazyGridPrefetchStrategy }
)
