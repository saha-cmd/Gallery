package com.gallery.core.ui.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Session-wide thread-safe registry tracking media thumbnail cache keys that have been loaded.
 */
object LoadedThumbnailRegistry {
    private val loadedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun markLoaded(key: String) {
        loadedKeys.add(key)
    }

    fun isLoaded(key: String): Boolean {
        return loadedKeys.contains(key)
    }

    fun clear() {
        loadedKeys.clear()
    }
}
