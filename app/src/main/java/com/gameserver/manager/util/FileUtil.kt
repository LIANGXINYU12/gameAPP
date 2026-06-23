package com.gameserver.manager.util

import android.util.Base64
import java.io.File

object FileUtil {

    fun decodeBase64(data: String): ByteArray {
        val pure = if (data.contains(",")) {
            data.substringAfter(",")
        } else {
            data
        }
        return Base64.decode(pure, Base64.DEFAULT)
    }

    fun writeBase64ToFile(data: String, targetFile: File): File {
        targetFile.parentFile?.mkdirs()
        targetFile.writeBytes(decodeBase64(data))
        return targetFile
    }

    fun sanitizeZipEntryName(name: String): String {
        return name.replace("\\", "/").removePrefix("/")
    }
}
