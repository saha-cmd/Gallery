package com.gallery.core.data.db

import androidx.room.Entity

@Entity(
    tableName = "user_album_group_members",
    primaryKeys = ["groupId", "albumKey"]
)
data class CustomAlbumGroupMemberEntity(
    val groupId: Long,
    val albumKey: String
)
