package com.gallery.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gallery.core.data.db.AlbumCoverDao
import com.gallery.core.data.db.AlbumGroupDao
import com.gallery.core.data.db.CustomAlbumDao
import com.gallery.core.data.db.FavoriteDao
import com.gallery.core.data.db.GalleryDatabase
import com.gallery.core.data.db.RecycleBinDao
import com.gallery.core.data.db.VaultDao
import com.gallery.core.data.repository.AppUpdateRepositoryImpl
import com.gallery.core.data.repository.MediaRepositoryImpl
import com.gallery.core.data.repository.RecycleBinRepositoryImpl
import com.gallery.core.data.repository.SettingsRepositoryImpl
import com.gallery.core.data.repository.VaultRepositoryImpl
import com.gallery.core.domain.repository.AppUpdateRepository
import com.gallery.core.domain.repository.MediaRepository
import com.gallery.core.domain.repository.RecycleBinRepository
import com.gallery.core.domain.repository.SettingsRepository
import com.gallery.core.domain.repository.VaultRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE vault_items ADD COLUMN albumName TEXT NOT NULL DEFAULT 'Default'")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `favorite_items` (`mediaId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `custom_albums` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `custom_album_items` (`albumId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`albumId`, `mediaId`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `album_groups` (`albumKey` TEXT NOT NULL, `groupName` TEXT NOT NULL, PRIMARY KEY(`albumKey`))"
            )
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `user_album_groups` (`groupId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `user_album_group_members` (`groupId` INTEGER NOT NULL, `albumKey` TEXT NOT NULL, PRIMARY KEY(`groupId`, `albumKey`))"
            )
            db.execSQL("ALTER TABLE `recycle_bin_items` ADD COLUMN `originalMediaId` INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE `recycle_bin_items` ADD COLUMN `bucketName` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `recycle_bin_items` ADD COLUMN `relativePath` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `recycle_bin_items` ADD COLUMN `dateAdded` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `recycle_bin_items` ADD COLUMN `dateTaken` INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE `recycle_bin_items` ADD COLUMN `trashedFilePath` TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `recycle_bin_items` SET `trashedFilePath` = `storedFilePath` WHERE `trashedFilePath` = ''")
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `album_covers` (`albumKey` TEXT NOT NULL, `coverUri` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`albumKey`))"
            )
            try {
                db.execSQL("ALTER TABLE `album_groups` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) {}
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GalleryDatabase {
        return Room.databaseBuilder(
            context,
            GalleryDatabase::class.java,
            "gallery_db"
        )
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
    }

    @Provides
    fun provideVaultDao(db: GalleryDatabase): VaultDao = db.vaultDao()

    @Provides
    fun provideRecycleBinDao(db: GalleryDatabase): RecycleBinDao = db.recycleBinDao()

    @Provides
    fun provideFavoriteDao(db: GalleryDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideCustomAlbumDao(db: GalleryDatabase): CustomAlbumDao = db.customAlbumDao()

    @Provides
    fun provideAlbumGroupDao(db: GalleryDatabase): AlbumGroupDao = db.albumGroupDao()

    @Provides
    fun provideAlbumCoverDao(db: GalleryDatabase): AlbumCoverDao = db.albumCoverDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository

    @Binds
    @Singleton
    abstract fun bindVaultRepository(impl: VaultRepositoryImpl): VaultRepository

    @Binds
    @Singleton
    abstract fun bindRecycleBinRepository(impl: RecycleBinRepositoryImpl): RecycleBinRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindAppUpdateRepository(impl: AppUpdateRepositoryImpl): AppUpdateRepository
}
