package com.gameserver.manager.storage

import com.gameserver.manager.GameServerApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConnectionRepository {

    private val dao = GameServerApp.instance.database.sshConnectionDao()

    suspend fun getAll(): List<SshConnectionEntity> = withContext(Dispatchers.IO) {
        dao.getAll()
    }

    suspend fun getById(id: Long): SshConnectionEntity? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    suspend fun save(connection: SshConnectionEntity): Long = withContext(Dispatchers.IO) {
        val entity = connection.copy(updatedAt = System.currentTimeMillis())
        if (entity.id == 0L) {
            dao.insert(entity)
        } else {
            dao.insert(entity)
            entity.id
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }
}
