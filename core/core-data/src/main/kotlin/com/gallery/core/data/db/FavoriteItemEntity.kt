package com.gallery.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_items")
data class FavoriteItemEntity(
    @PrimaryKey
    val mediaId: Long,
    val addedAt: Long = System.currentTimeMillis()
)
