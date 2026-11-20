package com.gallery.core.ui.components

/**
 * Lightweight data class representing the exact scroll position of a lazy grid.
 *
 * @property index The zero-based index of the first visible item.
 * @property offset The scroll offset of the first visible item in pixels.
 */
data class GridScrollPosition(
    val index: Int = 0,
    val offset: Int = 0
)
