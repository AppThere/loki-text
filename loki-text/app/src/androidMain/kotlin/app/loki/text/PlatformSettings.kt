package app.loki.text

import java.io.File

private fun settingsFile(): File? = activityFilesDir()?.let { File(it, "settings.json") }

actual fun loadSettingsText(): String? = runCatching { settingsFile()?.takeIf { it.isFile }?.readText() }.getOrNull()

actual fun saveSettingsText(text: String) {
    runCatching {
        val f = settingsFile() ?: return
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

actual fun fileExists(path: String): Boolean = File(path).isFile

actual fun nowMillis(): Long = System.currentTimeMillis()
