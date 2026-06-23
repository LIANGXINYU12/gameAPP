package com.gameserver.manager.agent

import com.gameserver.manager.ssh.SshConnectParams
import com.gameserver.manager.ssh.SshSessionManager
import com.gameserver.manager.sftp.SftpManager
import java.io.File

data class AgentDeployConfig(
    val remoteBaseDir: String = "/opt/game-agent",
    val remoteArchivePath: String = "/tmp/agent.tar.gz",
    val serviceName: String = "game-agent"
)

class AgentDeployManager(
    private val sshSessionManager: SshSessionManager,
    private val sftpManager: SftpManager
) {

    fun uploadArchive(sessionKey: String, localArchivePath: String, remotePath: String = AgentDeployConfig().remoteArchivePath) {
        val file = File(localArchivePath)
        require(file.exists()) { "Agent archive not found: $localArchivePath" }
        sftpManager.upload(sessionKey, localArchivePath, remotePath)
    }

    fun install(
        sessionKey: String,
        localArchivePath: String,
        config: AgentDeployConfig = AgentDeployConfig()
    ): String {
        uploadArchive(sessionKey, localArchivePath, config.remoteArchivePath)

        val commands = """
            set -e
            mkdir -p ${config.remoteBaseDir}/{agent,config,logs,data,backup}
            tar -xzf ${config.remoteArchivePath} -C ${config.remoteBaseDir}
            chmod +x ${config.remoteBaseDir}/agent/game-agent || chmod +x ${config.remoteBaseDir}/agent/agent || true
            rm -f ${config.remoteArchivePath}
            systemctl daemon-reload || true
            systemctl enable ${config.serviceName} || true
            systemctl restart ${config.serviceName} || true
            systemctl is-active ${config.serviceName} || echo "service check skipped"
        """.trimIndent()

        val result = sshSessionManager.exec(sessionKey, commands, timeoutSeconds = 300)
        if (result.exitCode != 0) {
            throw IllegalStateException("Agent install failed: ${result.stderr.ifBlank { result.stdout }}")
        }
        return result.stdout
    }

    fun reinstall(
        sessionKey: String,
        localArchivePath: String,
        config: AgentDeployConfig = AgentDeployConfig()
    ): String {
        uploadArchive(sessionKey, localArchivePath, config.remoteArchivePath)

        val commands = """
            set -e
            systemctl stop ${config.serviceName} || true
            mkdir -p ${config.remoteBaseDir}/backup/pre_reinstall_${'$'}(date +%Y%m%d_%H%M%S)
            cp -a ${config.remoteBaseDir}/config ${config.remoteBaseDir}/backup/pre_reinstall_${'$'}(date +%Y%m%d_%H%M%S)/ || true
            cp -a ${config.remoteBaseDir}/logs ${config.remoteBaseDir}/backup/pre_reinstall_${'$'}(date +%Y%m%d_%H%M%S)/ || true
            cp -a ${config.remoteBaseDir}/data ${config.remoteBaseDir}/backup/pre_reinstall_${'$'}(date +%Y%m%d_%H%M%S)/ || true
            rm -rf ${config.remoteBaseDir}/agent
            mkdir -p ${config.remoteBaseDir}/agent
            tar -xzf ${config.remoteArchivePath} -C ${config.remoteBaseDir}
            chmod +x ${config.remoteBaseDir}/agent/game-agent || chmod +x ${config.remoteBaseDir}/agent/agent || true
            rm -f ${config.remoteArchivePath}
            systemctl daemon-reload || true
            systemctl restart ${config.serviceName} || true
            systemctl is-active ${config.serviceName} || echo "service check skipped"
        """.trimIndent()

        val result = sshSessionManager.exec(sessionKey, commands, timeoutSeconds = 300)
        if (result.exitCode != 0) {
            throw IllegalStateException("Agent reinstall failed: ${result.stderr.ifBlank { result.stdout }}")
        }
        return result.stdout
    }

    fun connectAndInstall(params: SshConnectParams, localArchivePath: String, config: AgentDeployConfig = AgentDeployConfig()): String {
        val sessionKey = sshSessionManager.connect(params)
        return install(sessionKey, localArchivePath, config)
    }
}
