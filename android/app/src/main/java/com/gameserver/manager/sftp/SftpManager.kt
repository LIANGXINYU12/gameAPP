package com.gameserver.manager.sftp

import com.gameserver.manager.ssh.SshSessionManager
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

data class RemoteFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long
)

class SftpManager(private val sshSessionManager: SshSessionManager) {

    fun upload(sessionKey: String, localPath: String, remotePath: String) {
        useSftp(sessionKey) { sftp ->
            sftp.put(localPath, remotePath)
        }
    }

    fun uploadBytes(sessionKey: String, data: ByteArray, remotePath: String) {
        val tempFile = File.createTempFile("sftp_upload_", ".tmp")
        try {
            tempFile.writeBytes(data)
            upload(sessionKey, tempFile.absolutePath, remotePath)
        } finally {
            tempFile.delete()
        }
    }

    fun download(sessionKey: String, remotePath: String, localPath: String) {
        useSftp(sessionKey) { sftp ->
            sftp.get(remotePath, localPath)
        }
    }

    fun readText(sessionKey: String, remotePath: String): String {
        return useSftp(sessionKey) { sftp ->
            sftp.open(remotePath).use { remoteFile ->
                remoteFile.readFully().toString(StandardCharsets.UTF_8)
            }
        }
    }

    fun writeText(sessionKey: String, remotePath: String, content: String) {
        useSftp(sessionKey) { sftp ->
            sftp.open(remotePath, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { remoteFile ->
                remoteFile.write(0, content.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    fun listDirectory(sessionKey: String, remotePath: String): List<RemoteFileEntry> {
        return useSftp(sessionKey) { sftp ->
            sftp.ls(remotePath).map { entry ->
                RemoteFileEntry(
                    name = entry.name,
                    path = if (remotePath.endsWith("/")) "$remotePath${entry.name}" else "$remotePath/${entry.name}",
                    isDirectory = entry.attributes.type == FileMode.Type.DIRECTORY,
                    size = entry.attributes.size,
                    modifiedAt = (entry.attributes.mtime ?: 0).toLong() * 1000L
                )
            }
        }
    }

    fun mkdirs(sessionKey: String, remotePath: String) {
        useSftp(sessionKey) { sftp ->
            createDirectoriesRecursively(sftp, remotePath)
        }
    }

    fun remove(sessionKey: String, remotePath: String) {
        useSftp(sessionKey) { sftp ->
            sftp.rm(remotePath)
        }
    }

    private fun createDirectoriesRecursively(sftp: SFTPClient, remotePath: String) {
        val normalized = remotePath.trimEnd('/')
        if (normalized.isEmpty() || normalized == "/") return

        val parts = normalized.split("/").filter { it.isNotEmpty() }
        var current = if (normalized.startsWith("/")) "/" else ""
        for (part in parts) {
            current = if (current == "/" || current.isEmpty()) "$current$part" else "$current/$part"
            runCatching { sftp.mkdir(current) }
        }
    }

    private fun <T> useSftp(sessionKey: String, block: (SFTPClient) -> T): T {
        val client = sshSessionManager.getClient(sessionKey)
        val sftp = client.newSFTPClient()
        return sftp.use { block(it) }
    }

    private fun RemoteFile.readFully(): ByteArray {
        val buffer = ByteArrayOutputStreamCompat()
        val chunk = ByteArray(8192)
        var offset = 0L
        while (true) {
            val read = read(offset, chunk, 0, chunk.size)
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            offset += read
        }
        return buffer.toByteArray()
    }

    private class ByteArrayOutputStreamCompat : java.io.ByteArrayOutputStream()
}
