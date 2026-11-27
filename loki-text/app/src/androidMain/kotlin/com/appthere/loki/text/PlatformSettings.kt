package com.appthere.loki.text

import java.io.File

private fun settingsFile(): File? = activityFilesDir()?.let { File(it, "settings.json") }

actual fun profileDir(): String? = activityFilesDir()?.path

actual fun loadSettingsText(): String? = runCatching { settingsFile()?.takeIf { it.isFile }?.readText() }.getOrNull()

actual fun saveSettingsText(text: String) {
    runCatching {
        val f = settingsFile() ?: return
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

actual fun nowMillis(): Long = System.currentTimeMillis()
