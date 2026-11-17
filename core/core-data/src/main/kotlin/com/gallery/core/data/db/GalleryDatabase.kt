package com.gallery.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [
        VaultItemEntity::class,
        RecycleBinItemEntity::class,
        FavoriteItemEntity::class,
        CustomAlbumEntity::class,
        CustomAlbumItemEntity::class,
        AlbumGroupEntity::class,
        CustomAlbumGroupEntity::class,
        CustomAlbumGroupMemberEntity::class,
        AlbumCoverEntity::class
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao
    abstract fun recycleBinDao(): RecycleBinDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun customAlbumDao(): CustomAlbumDao
    abstract fun albumGroupDao(): AlbumGroupDao
    abstract fun albumCoverDao(): AlbumCoverDao
}

class Converters {
    @TypeConverter
    fun fromByteArray(value: ByteArray): String = android.util.Base64.encodeToString(value, android.util.Base64.DEFAULT)

    @TypeConverter
    fun toByteArray(value: String): ByteArray = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
}
