package com.gameserver.manager.hotupdate

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

data class WebBundleVersion(
    val version: String,
    val buildTime: String? = null
)

class HotUpdateManager(private val context: Context) {

    private val gson = Gson()

    val webRootDir: File
        get() = File(context.filesDir, WEB_ROOT_DIR_NAME).also { it.mkdirs() }

    val bundledVersion: WebBundleVersion
        get() = readVersionFromAssets() ?: WebBundleVersion(version = "0.0.0")

    val currentVersion: WebBundleVersion
        get() {
            val versionFile = File(webRootDir, VERSION_FILE_NAME)
            if (versionFile.exists()) {
                return runCatching {
                    gson.fromJson(versionFile.readText(), WebBundleVersion::class.java)
                }.getOrNull() ?: bundledVersion
            }
            return bundledVersion
        }

    fun getEntryUrl(): String {
        ensureBundledCopiedIfNeeded()
        val indexFile = File(webRootDir, INDEX_FILE_NAME)
        return if (indexFile.exists()) {
            "file://${indexFile.absolutePath}"
        } else {
            "file:///android_asset/$ASSET_WEB_DIR/$INDEX_FILE_NAME"
        }
    }

    fun applyUpdateZip(zipFile: File): WebBundleVersion {
        require(zipFile.exists()) { "Update zip not found: ${zipFile.absolutePath}" }

        val tempDir = File(context.cacheDir, "web_update_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            unzip(zipFile, tempDir)

            val newVersion = readVersionFromDir(tempDir)
                ?: throw IllegalStateException("version.json not found in update package")

            val backupDir = File(context.filesDir, "www_backup_${System.currentTimeMillis()}")
            if (webRootDir.exists()) {
                webRootDir.copyRecursively(backupDir, overwrite = true)
            }

            try {
                clearDirectory(webRootDir)
                tempDir.copyRecursively(webRootDir, overwrite = true)
            } catch (e: Exception) {
                if (backupDir.exists()) {
                    clearDirectory(webRootDir)
                    backupDir.copyRecursively(webRootDir, overwrite = true)
                }
                throw e
            } finally {
                backupDir.deleteRecursively()
            }

            markBundledCopied()
            return newVersion
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun applyUpdateFromBytes(data: ByteArray): WebBundleVersion {
        val tempZip = File(context.cacheDir, "hotupdate_${System.currentTimeMillis()}.zip")
        tempZip.writeBytes(data)
        return try {
            applyUpdateZip(tempZip)
        } finally {
            tempZip.delete()
        }
    }

    private fun ensureBundledCopiedIfNeeded() {
        val marker = File(context.filesDir, BUNDLED_COPY_MARKER)
        if (marker.exists() && File(webRootDir, INDEX_FILE_NAME).exists()) {
            return
        }
        copyBundledAssetsToWebRoot()
        markBundledCopied()
    }

    private fun copyBundledAssetsToWebRoot() {
        clearDirectory(webRootDir)
        copyAssetFolder(ASSET_WEB_DIR, webRootDir)
    }

    private fun copyAssetFolder(assetPath: String, targetDir: File) {
        val assets = context.assets.list(assetPath) ?: return
        if (assets.isEmpty()) {
            targetDir.mkdirs()
            return
        }
        targetDir.mkdirs()
        for (name in assets) {
            val childAssetPath = "$assetPath/$name"
            val childTarget = File(targetDir, name)
            val children = context.assets.list(childAssetPath)
            if (children.isNullOrEmpty()) {
                context.assets.open(childAssetPath).use { input ->
                    FileOutputStream(childTarget).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                copyAssetFolder(childAssetPath, childTarget)
            }
        }
    }

    private fun readVersionFromAssets(): WebBundleVersion? {
        return runCatching {
            context.assets.open("$ASSET_WEB_DIR/$VERSION_FILE_NAME").bufferedReader().use { reader ->
                gson.fromJson(reader, WebBundleVersion::class.java)
            }
        }.getOrNull()
    }

    private fun readVersionFromDir(dir: File): WebBundleVersion? {
        val versionFile = File(dir, VERSION_FILE_NAME)
        if (!versionFile.exists()) return null
        return gson.fromJson(versionFile.readText(), WebBundleVersion::class.java)
    }

    private fun markBundledCopied() {
        File(context.filesDir, BUNDLED_COPY_MARKER).writeText(System.currentTimeMillis().toString())
    }

    private fun clearDirectory(dir: File) {
        if (!dir.exists()) {
            dir.mkdirs()
            return
        }
        dir.listFiles()?.forEach { file ->
            file.deleteRecursively()
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        const val WEB_ROOT_DIR_NAME = "www"
        const val ASSET_WEB_DIR = "www"
        const val VERSION_FILE_NAME = "version.json"
        const val INDEX_FILE_NAME = "index.html"
        private const val BUNDLED_COPY_MARKER = "www_bundled_copied.marker"
    }
}
