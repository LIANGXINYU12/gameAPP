package com.gameserver.manager.bridge

import android.util.Log
import android.webkit.WebView
import com.gameserver.manager.BuildConfig
import com.gameserver.manager.GameServerApp
import com.gameserver.manager.agent.AgentDeployConfig
import com.gameserver.manager.agent.AgentDeployManager
import com.gameserver.manager.hotupdate.HotUpdateManager
import com.gameserver.manager.sftp.SftpManager
import com.gameserver.manager.ssh.SshConnectParams
import com.gameserver.manager.ssh.SshSessionManager
import com.gameserver.manager.storage.ConnectionRepository
import com.gameserver.manager.storage.SettingsRepository
import com.gameserver.manager.storage.SshConnectionEntity
import com.gameserver.manager.util.FileUtil
import com.gameserver.manager.util.JsonUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class NativeBridge(
    private val webView: WebView,
    private val scope: CoroutineScope,
    private val hotUpdateManager: HotUpdateManager,
    private val onReloadWebView: () -> Unit,
    private val onPickFile: (mimeTypes: Array<String>, callbackId: String) -> Unit
) {

    private val sshSessionManager: SshSessionManager = GameServerApp.instance.sshSessionManager
    private val sftpManager = SftpManager(sshSessionManager)
    private val agentDeployManager = AgentDeployManager(sshSessionManager, sftpManager)
    private val connectionRepository = ConnectionRepository()
    private val settingsRepository = SettingsRepository()

    @android.webkit.JavascriptInterface
    fun getShellInfo(): String {
        return JsonUtil.success(
            mapOf(
                "shellVersion" to BuildConfig.SHELL_VERSION,
                "appVersion" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "webVersion" to hotUpdateManager.currentVersion.version,
                "bundledWebVersion" to hotUpdateManager.bundledVersion.version,
                "webRoot" to hotUpdateManager.webRootDir.absolutePath
            )
        )
    }

    @android.webkit.JavascriptInterface
    fun ping(): String = JsonUtil.success("pong")

    @android.webkit.JavascriptInterface
    fun call(method: String, paramsJson: String, callbackId: String) {
        scope.launch {
            if (method == "file.pick") {
                val params = if (paramsJson.isBlank()) emptyMap() else JsonUtil.parseMap(paramsJson)
                val mime = JsonUtil.getString(params, "mimeType", "*/*")
                onPickFile(arrayOf(mime), callbackId)
                return@launch
            }

            val result = runCatching {
                dispatch(method, paramsJson)
            }.getOrElse { throwable ->
                Log.e(TAG, "Native call failed: $method", throwable)
                JsonUtil.error(throwable.message ?: "unknown error")
            }
            deliverCallback(callbackId, result)
        }
    }

    private suspend fun dispatch(method: String, paramsJson: String): String {
        val params = if (paramsJson.isBlank()) emptyMap() else JsonUtil.parseMap(paramsJson)
        return when (method) {
            "storage.getConnections" -> getConnections()
            "storage.saveConnection" -> saveConnection(params)
            "storage.deleteConnection" -> deleteConnection(params)
            "storage.getSetting" -> getSetting(params)
            "storage.setSetting" -> setSetting(params)
            "ssh.connect" -> sshConnect(params)
            "ssh.connectById" -> sshConnectById(params)
            "ssh.exec" -> sshExec(params)
            "ssh.disconnect" -> sshDisconnect(params)
            "ssh.isConnected" -> sshIsConnected(params)
            "sftp.upload" -> sftpUpload(params)
            "sftp.download" -> sftpDownload(params)
            "sftp.list" -> sftpList(params)
            "sftp.readText" -> sftpReadText(params)
            "sftp.writeText" -> sftpWriteText(params)
            "sftp.mkdirs" -> sftpMkdirs(params)
            "hotupdate.getVersion" -> hotUpdateGetVersion()
            "hotupdate.applyZipPath" -> hotUpdateApplyZipPath(params)
            "hotupdate.applyZipBase64" -> hotUpdateApplyZipBase64(params)
            "hotupdate.reload" -> hotUpdateReload()
            "agent.upload" -> agentUpload(params)
            "agent.install" -> agentInstall(params)
            "agent.reinstall" -> agentReinstall(params)
            else -> JsonUtil.error("Unknown method: $method")
        }
    }

    private suspend fun getConnections(): String = withContext(Dispatchers.IO) {
        val list = connectionRepository.getAll().map { it.toMap(includeSecrets = false) }
        JsonUtil.success(list)
    }

    private suspend fun saveConnection(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val entity = SshConnectionEntity(
            id = JsonUtil.getLong(params, "id", 0L),
            name = JsonUtil.getString(params, "name"),
            host = JsonUtil.getString(params, "host"),
            port = JsonUtil.getInt(params, "port", 22),
            username = JsonUtil.getString(params, "username"),
            authType = JsonUtil.getString(params, "authType", "password"),
            password = params["password"]?.toString(),
            privateKey = params["privateKey"]?.toString(),
            passphrase = params["passphrase"]?.toString(),
            isFavorite = JsonUtil.getBoolean(params, "isFavorite", false)
        )
        require(entity.name.isNotBlank()) { "name is required" }
        require(entity.host.isNotBlank()) { "host is required" }
        require(entity.username.isNotBlank()) { "username is required" }

        val id = connectionRepository.save(entity)
        JsonUtil.success(mapOf("id" to id))
    }

    private suspend fun deleteConnection(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val id = JsonUtil.getLong(params, "id")
        require(id > 0) { "id is required" }
        connectionRepository.delete(id)
        sshSessionManager.disconnect("conn_$id")
        JsonUtil.success(true)
    }

    private suspend fun getSetting(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val key = JsonUtil.getString(params, "key")
        require(key.isNotBlank()) { "key is required" }
        JsonUtil.success(settingsRepository.get(key))
    }

    private suspend fun setSetting(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val key = JsonUtil.getString(params, "key")
        val value = JsonUtil.getString(params, "value")
        require(key.isNotBlank()) { "key is required" }
        settingsRepository.set(key, value)
        JsonUtil.success(true)
    }

    private suspend fun sshConnect(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val connectParams = params.toConnectParams()
        val sessionKey = sshSessionManager.connect(connectParams)
        JsonUtil.success(mapOf("sessionKey" to sessionKey))
    }

    private suspend fun sshConnectById(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val id = JsonUtil.getLong(params, "id")
        val connection = connectionRepository.getById(id)
            ?: throw IllegalArgumentException("Connection not found: $id")
        val sessionKey = sshSessionManager.connect(connection)
        JsonUtil.success(mapOf("sessionKey" to sessionKey))
    }

    private suspend fun sshExec(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val sessionKey = JsonUtil.getString(params, "sessionKey")
        val command = JsonUtil.getString(params, "command")
        val timeout = JsonUtil.getInt(params, "timeoutSeconds", 120)
        require(sessionKey.isNotBlank()) { "sessionKey is required" }
        require(command.isNotBlank()) { "command is required" }
        val result = sshSessionManager.exec(sessionKey, command, timeout)
        JsonUtil.success(result)
    }

    private suspend fun sshDisconnect(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val sessionKey = JsonUtil.getString(params, "sessionKey")
        require(sessionKey.isNotBlank()) { "sessionKey is required" }
        sshSessionManager.disconnect(sessionKey)
        JsonUtil.success(true)
    }

    private suspend fun sshIsConnected(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val sessionKey = JsonUtil.getString(params, "sessionKey")
        JsonUtil.success(sshSessionManager.isConnected(sessionKey))
    }

    private suspend fun sftpUpload(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val sessionKey = JsonUtil.getString(params, "sessionKey")
        val localPath = JsonUtil.getString(params, "localPath")
        val remotePath = JsonUtil.getString(params, "remotePath")
        require(sessionKey.isNotBlank() && localPath.isNotBlank() && remotePath.isNotBlank()) {
            "sessionKey, localPath and remotePath are required"
        }
        sftpManager.upload(sessionKey, localPath, remotePath)
        JsonUtil.success(true)
    }

    private suspend fun sftpDownload(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val sessionKey = JsonUtil.getString(params, "sessionKey")
        val remotePath = JsonUtil.getString(params, "remotePath")
        val localPath = JsonUtil.getString(params, "localPath")
        require(sessionKey.isNotBlank() && remotePath.isNotBlank() && localPath.isNotBlank()) {
            "sessionKey, remotePath and localPath are required"
        }
        sftpManager.download(sessionKey, remotePath, localPath)
        JsonUtil.success(true)
    }

    private suspend fun sftpList(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val sessionKey = JsonUtil.getString(params, "sessionKey")
        val remotePath = JsonUtil.getString(params, "remotePath", "/")
        val list = sftpManager.listDirectory(sessionKey, remotePath)
        JsonUtil.success(list)
    }

    private suspend fun sftpReadText(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val sessionKey = JsonUtil.getString(params, "sessionKey")
        val remotePath = JsonUtil.getString(params, "remotePath")
        val content = sftpManager.readText(sessionKey, remotePath)
        JsonUtil.success(content)
    }

    private suspend fun sftpWriteText(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val sessionKey = JsonUtil.getString(params, "sessionKey")
        val remotePath = JsonUtil.getString(params, "remotePath")
        val content = JsonUtil.getString(params, "content")
        sftpManager.writeText(sessionKey, remotePath, content)
        JsonUtil.success(true)
    }

    private suspend fun sftpMkdirs(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val sessionKey = JsonUtil.getString(params, "sessionKey")
        val remotePath = JsonUtil.getString(params, "remotePath")
        sftpManager.mkdirs(sessionKey, remotePath)
        JsonUtil.success(true)
    }

    private fun hotUpdateGetVersion(): String {
        return JsonUtil.success(
            mapOf(
                "current" to hotUpdateManager.currentVersion,
                "bundled" to hotUpdateManager.bundledVersion,
                "entryUrl" to hotUpdateManager.getEntryUrl()
            )
        )
    }

    private suspend fun hotUpdateApplyZipPath(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val zipPath = JsonUtil.getString(params, "zipPath")
        require(zipPath.isNotBlank()) { "zipPath is required" }
        val version = hotUpdateManager.applyUpdateZip(File(zipPath))
        JsonUtil.success(version)
    }

    private suspend fun hotUpdateApplyZipBase64(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val base64 = JsonUtil.getString(params, "base64")
        require(base64.isNotBlank()) { "base64 is required" }
        val version = hotUpdateManager.applyUpdateFromBytes(FileUtil.decodeBase64(base64))
        JsonUtil.success(version)
    }

    private fun hotUpdateReload(): String {
        onReloadWebView()
        return JsonUtil.success(true)
    }

    private suspend fun agentUpload(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val sessionKey = JsonUtil.getString(params, "sessionKey")
        val localPath = JsonUtil.getString(params, "localPath")
        val remotePath = JsonUtil.getString(params, "remotePath", AgentDeployConfig().remoteArchivePath)
        agentDeployManager.uploadArchive(sessionKey, localPath, remotePath)
        JsonUtil.success(mapOf("remotePath" to remotePath))
    }

    private suspend fun agentInstall(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val sessionKey = resolveSessionKey(params)
        val localPath = JsonUtil.getString(params, "localPath")
        val config = params.toAgentDeployConfig()
        val output = agentDeployManager.install(sessionKey, localPath, config)
        JsonUtil.success(mapOf("output" to output))
    }

    private suspend fun agentReinstall(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val sessionKey = resolveSessionKey(params)
        val localPath = JsonUtil.getString(params, "localPath")
        val config = params.toAgentDeployConfig()
        val output = agentDeployManager.reinstall(sessionKey, localPath, config)
        JsonUtil.success(mapOf("output" to output))
    }

    private suspend fun resolveSessionKey(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val existing = JsonUtil.getString(params, "sessionKey")
        if (existing.isNotBlank() && sshSessionManager.isConnected(existing)) {
            return@withContext existing
        }

        val connectionId = JsonUtil.getLong(params, "connectionId", 0L)
        if (connectionId > 0) {
            val connection = connectionRepository.getById(connectionId)
                ?: throw IllegalArgumentException("Connection not found: $connectionId")
            return@withContext sshSessionManager.connect(connection)
        }

        return@withContext sshSessionManager.connect(params.toConnectParams())
    }

    fun deliverCallback(callbackId: String, resultJson: String) {
        val escaped = resultJson
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        webView.post {
            webView.evaluateJavascript(
                "window.__onNativeCallback && window.__onNativeCallback('$callbackId', '$escaped');",
                null
            )
        }
    }

    private fun Map<String, Any?>.toConnectParams(): SshConnectParams {
        return SshConnectParams(
            host = JsonUtil.getString(this, "host"),
            port = JsonUtil.getInt(this, "port", 22),
            username = JsonUtil.getString(this, "username"),
            authType = JsonUtil.getString(this, "authType", "password"),
            password = this["password"]?.toString(),
            privateKey = this["privateKey"]?.toString(),
            passphrase = this["passphrase"]?.toString(),
            connectionId = JsonUtil.getLong(this, "connectionId", 0L).takeIf { it > 0 },
            timeoutSeconds = JsonUtil.getInt(this, "timeoutSeconds", 30)
        )
    }

    private fun Map<String, Any?>.toAgentDeployConfig(): AgentDeployConfig {
        return AgentDeployConfig(
            remoteBaseDir = JsonUtil.getString(this, "remoteBaseDir", "/opt/game-agent"),
            remoteArchivePath = JsonUtil.getString(this, "remoteArchivePath", "/tmp/agent.tar.gz"),
            serviceName = JsonUtil.getString(this, "serviceName", "game-agent")
        )
    }

    private fun SshConnectionEntity.toMap(includeSecrets: Boolean): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "name" to name,
            "host" to host,
            "port" to port,
            "username" to username,
            "authType" to authType,
            "password" to if (includeSecrets) password else null,
            "privateKey" to if (includeSecrets) privateKey else null,
            "passphrase" to if (includeSecrets) passphrase else null,
            "isFavorite" to isFavorite,
            "createdAt" to createdAt,
            "updatedAt" to updatedAt
        )
    }

    companion object {
        private const val TAG = "NativeBridge"
        const val JS_BRIDGE_NAME = "NativeBridge"
    }
}
