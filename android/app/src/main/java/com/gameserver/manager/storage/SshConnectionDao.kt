package com.gameserver.manager.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface SshConnectionDao {

    @Query("SELECT * FROM ssh_connections ORDER BY isFavorite DESC, updatedAt DESC")
    suspend fun getAll(): List<SshConnectionEntity>

    @Query("SELECT * FROM ssh_connections WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SshConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connection: SshConnectionEntity): Long

    @Update
    suspend fun update(connection: SshConnectionEntity)

    @Delete
    suspend fun delete(connection: SshConnectionEntity)

    @Query("DELETE FROM ssh_connections WHERE id = :id")
    suspend fun deleteById(id: Long)
}
