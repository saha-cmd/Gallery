package com.gallery.core.domain.model

data class UserAlbumGroup(
    val id: Long,
    val name: String,
    val albumKeys: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)
