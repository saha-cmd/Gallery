package com.gallery.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_album_groups")
data class CustomAlbumGroupEntity(
    @PrimaryKey(autoGenerate = true)
    val groupId: Long = 0,
    val groupName: String,
    val createdAt: Long = System.currentTimeMillis()
)
