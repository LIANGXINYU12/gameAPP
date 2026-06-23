package com.gameserver.manager.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ssh_connections")
data class SshConnectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: String,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
