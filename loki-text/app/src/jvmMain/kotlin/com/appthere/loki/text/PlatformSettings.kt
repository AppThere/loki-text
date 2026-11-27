package com.appthere.loki.text

import java.io.File

/** `$XDG_CONFIG_HOME/loki-text`, else `~/.config/loki-text` (`~/Library/Application Support` on macOS, `%APPDATA%` on Windows). */
private fun settingsFile(): File {
    val home = System.getProperty("user.home").orEmpty()
    val os = System.getProperty("os.name").orEmpty()
    val base = when {
        os.startsWith("Mac") -> File(home, "Library/Application Support")
        os.startsWith("Windows") -> File(System.getenv("APPDATA") ?: home)
        else -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config")
    }
    return File(base, "loki-text/settings.json")
}

actual fun profileDir(): String? = settingsFile().parentFile?.path

actual fun loadSettingsText(): String? = runCatching { settingsFile().takeIf { it.isFile }?.readText() }.getOrNull()

actual fun saveSettingsText(text: String) {
    runCatching {
        val f = settingsFile()
        f.parentFile.mkdirs()
        // Write beside it and rename, so a crash mid-write leaves the old file whole.
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

actual fun documentSource(path: String): String? = null

actual fun recentAvailable(r: RecentDocument): Boolean = File(r.path).isFile

actual fun materializeRecent(r: RecentDocument): String? = r.path.takeIf { File(it).isFile }

actual fun nowMillis(): Long = System.currentTimeMillis()
