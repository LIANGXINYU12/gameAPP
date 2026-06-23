package com.gameserver.manager

import android.app.Application
import com.gameserver.manager.storage.AppDatabase
import com.gameserver.manager.ssh.SshSessionManager

class GameServerApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var sshSessionManager: SshSessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getInstance(this)
        sshSessionManager = SshSessionManager()
    }

    companion object {
        lateinit var instance: GameServerApp
            private set
    }
}
