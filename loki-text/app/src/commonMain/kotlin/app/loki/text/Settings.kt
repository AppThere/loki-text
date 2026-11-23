package app.loki.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The text of the settings file, or null when there is none yet or it can't be read. Blocks briefly. */
expect fun loadSettingsText(): String?

/** A folder the app owns and keeps between runs (the one the settings file is in), or null when there is none yet. */
expect fun profileDir(): String?

/** Replaces the settings file. Failure is silent: a setting that can't be saved is only forgotten. */
expect fun saveSettingsText(text: String)

/**
 * Where the document open at [path] really lives, when that is not a file path (Android: the content URI the user picked,
 * which [path] is a copy of); null when the path is the document.
 */
expect fun documentSource(path: String): String?

/** Whether [r] can still be opened: its file is there, or the system still lets the app read where it came from. */
expect fun recentAvailable(r: RecentDocument): Boolean

/** A path the engine can open for [r] (on Android a fresh copy of the document, so edits made elsewhere show), or null when it can't be had. */
expect fun materializeRecent(r: RecentDocument): String?

/** Milliseconds since 1970. */
expect fun nowMillis(): Long

/** A document that was opened lately: where it is and when it was last opened. */
data class RecentDocument(val path: String, val openedAt: Long, val uri: String? = null) {
    /** What identifies the document: where it came from when that is not a file path, else the path. */
    val key: String get() = uri ?: path

    /** The file's name, as the start screen lists it. */
    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
    /** The folder it is in, for telling apart two files of the same name. */
    val folder: String get() = if (uri != null) "" else path.lastIndexOfAny(charArrayOf('/', '\\')).let { if (it < 0) "" else path.substring(0, it) }
}

/**
 * What the app keeps between launches: the interface theme, the screen paper colour, the recent documents, and the
 * person's key changes and ribbon layout (as the engine reports them; null while they have changed nothing).
 */
data class Settings(
    val theme: String = "system",
    val paperColor: String? = null,
    val recents: List<RecentDocument> = emptyList(),
    val keys: JsonArray? = null,
    val ribbon: JsonObject? = null,
) {
    /** These settings with [path] first in the recent list (once, whatever it was before). */
    fun withRecent(path: String, at: Long, uri: String? = null): Settings {
        val r = RecentDocument(path, at, uri)
        return copy(recents = (listOf(r) + recents.filter { it.key != r.key }).take(MAX_RECENTS))
    }

    /** These settings without [r] in the recent list. */
    fun withoutRecent(r: RecentDocument): Settings = copy(recents = recents.filter { it.key != r.key })

    fun toJson(): String = buildJsonObject {
        put("theme", theme)
        paperColor?.let { put("paperColor", it) }
        put("recents", buildJsonArray {
            for (r in recents) add(buildJsonObject { put("path", r.path); put("openedAt", r.openedAt); r.uri?.let { put("uri", it) } })
        })
        keys?.let { put("keys", it) }
        ribbon?.let { put("ribbon", it) }
    }.toString()

    companion object {
        const val MAX_RECENTS = 20

        /** Settings from [text]; anything unreadable or out of range falls back to the default, so a damaged file never stops the app. */
        fun parse(text: String?): Settings {
            val o = runCatching { Json.parseToJsonElement(text ?: return Settings()) as? JsonObject }.getOrNull() ?: return Settings()
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val recents = (o["recents"] as? JsonArray).orEmpty().mapNotNull { e ->
                val r = e as? JsonObject ?: return@mapNotNull null
                val path = (r["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RecentDocument(
                    path, r["openedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    (r["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() },
                )
            }.distinctBy { it.key }.take(MAX_RECENTS)
            return Settings(
                theme = str("theme")?.takeIf { it in setOf("system", "light", "dark") } ?: "system",
                paperColor = str("paperColor")?.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) },
                recents = recents,
                keys = o["keys"] as? JsonArray,
                ribbon = o["ribbon"] as? JsonObject,
            )
        }
    }
}

/** The settings of this run, shared by every window: changing them writes the file. */
object AppSettings {
    private var current: Settings? = null

    val value: Settings get() = current ?: Settings.parse(loadSettingsText()).also { current = it }

    fun update(change: (Settings) -> Settings) {
        val next = change(value)
        if (next == current) return
        current = next
        saveSettingsText(next.toJson())
    }
}

/** A recent document's age as the start screen words it: "Today", "Yesterday", "3 days ago", or "" when it has no date. */
internal fun ageLabel(openedAt: Long, now: Long): String {
    if (openedAt <= 0L) return ""
    val days = ((now - openedAt) / 86_400_000L).toInt()
    return when {
        days < 1 -> "Today"
        days == 1 -> "Yesterday"
        days < 30 -> "$days days ago"
        days < 365 -> "${days / 30} ${if (days / 30 == 1) "month" else "months"} ago"
        else -> "${days / 365} ${if (days / 365 == 1) "year" else "years"} ago"
    }
}
