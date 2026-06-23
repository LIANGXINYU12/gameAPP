package com.gameserver.manager.storage

import com.gameserver.manager.GameServerApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SettingsRepository {

    private val dao = GameServerApp.instance.database.appSettingDao()

    suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        dao.getValue(key)
    }

    suspend fun set(key: String, value: String) = withContext(Dispatchers.IO) {
        dao.setValue(AppSettingEntity(key, value))
    }

    suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        dao.delete(key)
    }
}
