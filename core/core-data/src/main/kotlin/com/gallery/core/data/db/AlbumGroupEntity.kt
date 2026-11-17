package com.gallery.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "album_groups")
data class AlbumGroupEntity(
    @PrimaryKey
    val albumKey: String,
    val groupName: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface AlbumGroupDao {
    @Query("SELECT * FROM album_groups")
    fun observeAll(): Flow<List<AlbumGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AlbumGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AlbumGroupEntity>)

    @Query("DELETE FROM album_groups WHERE albumKey IN (:albumKeys)")
    suspend fun deleteByAlbumKeys(albumKeys: List<String>)

    // ── Legacy album_groups queries ──────────────────────────────────────────
    @Query("SELECT * FROM album_groups")
    fun getAllAlbumGroups(): Flow<List<AlbumGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setAlbumGroup(entity: AlbumGroupEntity)

    @Query("DELETE FROM album_groups WHERE albumKey = :albumKey")
    suspend fun deleteAlbumGroup(albumKey: String)

    // ── User Album Groups queries ────────────────────────────────────────────
    @Query("SELECT * FROM user_album_groups ORDER BY createdAt ASC")
    fun observeAllGroups(): Flow<List<CustomAlbumGroupEntity>>

    @Query("SELECT * FROM user_album_group_members")
    fun observeAllMembers(): Flow<List<CustomAlbumGroupMemberEntity>>

    @Query("SELECT * FROM user_album_groups ORDER BY createdAt ASC")
    suspend fun getAllGroups(): List<CustomAlbumGroupEntity>

    @Query("SELECT * FROM user_album_group_members WHERE groupId = :groupId")
    suspend fun getMembersForGroup(groupId: Long): List<CustomAlbumGroupMemberEntity>

    @Query("SELECT * FROM user_album_groups WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupById(groupId: Long): CustomAlbumGroupEntity?

    @Query("SELECT * FROM user_album_groups WHERE LOWER(groupName) = LOWER(:groupName) LIMIT 1")
    suspend fun getGroupByName(groupName: String): CustomAlbumGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: CustomAlbumGroupEntity): Long

    @Update
    suspend fun updateGroup(group: CustomAlbumGroupEntity)

    @Query("UPDATE user_album_groups SET groupName = :newName WHERE groupId = :groupId")
    suspend fun renameGroupOnly(groupId: Long, newName: String)

    @Query("DELETE FROM user_album_groups WHERE groupId = :groupId")
    suspend fun deleteGroupOnly(groupId: Long)

    @Query("DELETE FROM user_album_group_members WHERE groupId = :groupId")
    suspend fun deleteMembersForGroup(groupId: Long)

    @Transaction
    suspend fun deleteGroup(groupId: Long) {
        val members = getMembersForGroup(groupId)
        val keys = members.map { it.albumKey }
        if (keys.isNotEmpty()) {
            deleteByAlbumKeys(keys)
        }
        deleteMembersForGroup(groupId)
        deleteGroupOnly(groupId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<CustomAlbumGroupMemberEntity>)

    @Query("DELETE FROM user_album_group_members WHERE groupId = :groupId AND albumKey = :albumKey")
    suspend fun deleteMember(groupId: Long, albumKey: String)

    @Query("DELETE FROM user_album_group_members WHERE albumKey = :albumKey")
    suspend fun removeAlbumFromAllGroups(albumKey: String)

    @Transaction
    suspend fun createGroupWithAlbums(groupName: String, albumKeys: List<String>): Long {
        val trimmed = groupName.trim()
        val existing = getGroupByName(trimmed)
        val groupId = existing?.groupId ?: insertGroup(CustomAlbumGroupEntity(groupName = trimmed))
        val distinctKeys = albumKeys.distinct()
        distinctKeys.forEach { key ->
            removeAlbumFromAllGroups(key)
        }
        val members = distinctKeys.map { key ->
            CustomAlbumGroupMemberEntity(groupId = groupId, albumKey = key)
        }
        if (members.isNotEmpty()) {
            insertMembers(members)
        }
        val now = System.currentTimeMillis()
        val entities = distinctKeys.map { key ->
            AlbumGroupEntity(albumKey = key, groupName = trimmed, updatedAt = now)
        }
        if (entities.isNotEmpty()) {
            upsertAll(entities)
        }
        return groupId
    }

    @Transaction
    suspend fun addAlbumsToGroup(groupId: Long, albumKeys: List<String>) {
        val group = getGroupById(groupId) ?: return
        val distinctKeys = albumKeys.distinct()
        distinctKeys.forEach { key ->
            removeAlbumFromAllGroups(key)
        }
        val members = distinctKeys.map { key ->
            CustomAlbumGroupMemberEntity(groupId = groupId, albumKey = key)
        }
        if (members.isNotEmpty()) {
            insertMembers(members)
        }
        val now = System.currentTimeMillis()
        val entities = distinctKeys.map { key ->
            AlbumGroupEntity(albumKey = key, groupName = group.groupName, updatedAt = now)
        }
        if (entities.isNotEmpty()) {
            upsertAll(entities)
        }
    }

    @Transaction
    suspend fun removeAlbumsFromGroup(groupId: Long, albumKeys: List<String>) {
        val distinctKeys = albumKeys.distinct()
        distinctKeys.forEach { key ->
            deleteMember(groupId, key)
        }
        deleteByAlbumKeys(distinctKeys)
    }

    @Transaction
    suspend fun renameGroup(groupId: Long, newName: String) {
        val trimmed = newName.trim()
        renameGroupOnly(groupId, trimmed)
        val members = getMembersForGroup(groupId)
        val now = System.currentTimeMillis()
        val entities = members.map { member ->
            AlbumGroupEntity(albumKey = member.albumKey, groupName = trimmed, updatedAt = now)
        }
        if (entities.isNotEmpty()) {
            upsertAll(entities)
        }
    }
}
