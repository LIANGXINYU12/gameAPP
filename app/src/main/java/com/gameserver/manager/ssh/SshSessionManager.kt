package com.gameserver.manager.ssh

import com.gameserver.manager.storage.SshConnectionEntity
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SshConnectParams(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: String,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val connectionId: Long? = null,
    val timeoutSeconds: Int = 30
)

data class SshExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

class SshSessionManager {

    private val sessions = ConcurrentHashMap<String, SSHClient>()

    fun connect(params: SshConnectParams): String {
        disconnect(params.sessionKey())

        val client = SSHClient()
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.timeout = params.timeoutSeconds * 1000
        client.connect(params.host, params.port)
        authenticate(client, params)

        sessions[params.sessionKey()] = client
        return params.sessionKey()
    }

    fun connect(connection: SshConnectionEntity): String {
        return connect(
            SshConnectParams(
                host = connection.host,
                port = connection.port,
                username = connection.username,
                authType = connection.authType,
                password = connection.password,
                privateKey = connection.privateKey,
                passphrase = connection.passphrase,
                connectionId = connection.id
            )
        )
    }

    fun exec(sessionKey: String, command: String, timeoutSeconds: Int = 120): SshExecResult {
        val client = sessions[sessionKey] ?: throw IllegalStateException("SSH session not found: $sessionKey")
        val session = client.startSession()
        session.use {
            val cmd = it.exec(command)
            cmd.join(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            return SshExecResult(
                exitCode = cmd.exitStatus ?: -1,
                stdout = IOUtils.readFully(cmd.inputStream).toString(StandardCharsets.UTF_8),
                stderr = IOUtils.readFully(cmd.errorStream).toString(StandardCharsets.UTF_8)
            )
        }
    }

    fun openShell(sessionKey: String): Session {
        val client = sessions[sessionKey] ?: throw IllegalStateException("SSH session not found: $sessionKey")
        return client.startSession()
    }

    fun getClient(sessionKey: String): SSHClient {
        return sessions[sessionKey] ?: throw IllegalStateException("SSH session not found: $sessionKey")
    }

    fun isConnected(sessionKey: String): Boolean {
        val client = sessions[sessionKey] ?: return false
        return client.isConnected && client.isAuthenticated
    }

    fun disconnect(sessionKey: String) {
        sessions.remove(sessionKey)?.let { client ->
            runCatching {
                if (client.isConnected) {
                    client.disconnect()
                }
            }
        }
    }

    fun disconnectAll() {
        sessions.keys.toList().forEach { disconnect(it) }
    }

    private fun authenticate(client: SSHClient, params: SshConnectParams) {
        when (params.authType.lowercase()) {
            "password" -> {
                val pwd = params.password ?: throw IllegalArgumentException("Password is required")
                client.authPassword(params.username, pwd)
            }
            "private_key", "privatekey", "key", "private_key_passphrase", "key_passphrase" -> {
                val keyContent = params.privateKey ?: throw IllegalArgumentException("Private key is required")
                val keyFile = OpenSSHKeyFile()
                keyFile.init(StringReader(keyContent), null, SimplePasswordFinder(params.passphrase))
                client.authPublickey(params.username, keyFile)
            }
            else -> throw IllegalArgumentException("Unsupported auth type: ${params.authType}")
        }
    }

    private fun SshConnectParams.sessionKey(): String {
        return connectionId?.let { "conn_$it" }
            ?: "adhoc_${host}_${port}_${username}_${System.currentTimeMillis()}"
    }

    private class SimplePasswordFinder(private val passphrase: String?) : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): Boolean = !passphrase.isNullOrEmpty()
        override fun resolve(resource: Resource<*>?): CharArray = passphrase?.toCharArray() ?: CharArray(0)
        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }
}
