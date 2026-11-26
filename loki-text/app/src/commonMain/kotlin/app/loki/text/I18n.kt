package app.loki.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.loki.text.res.Res
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp

/** The operating system's language as a BCP 47 tag (`de-DE`, `pt-BR`). */
expect fun systemLanguage(): String

/**
 * Interface languages. Strings stay English in the code and are the lookup keys; craft-i18n's catalogs map them to the
 * chosen language when the UI is drawn (the desktop front end's way). Command ids, document text and file names are never
 * translated. A string without a translation shows in English, so coverage grows an entry at a time.
 */
object I18n {
    /** English → translation for the language in use; empty for English. */
    var table by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** The language in use (a catalog's code), "en" for English. */
    var current by mutableStateOf("en")
        private set

    /** Bumped whenever the table changes, for what reads labels once (the command list). */
    var version by mutableStateOf(0)
        private set

    /** The catalogs this build has, by code (`de`, `pt-br`). */
    suspend fun available(): List<String> =
        runCatching { Res.readBytes("files/i18n/locales.txt").decodeToString() }.getOrDefault("").lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Uses [choice] ("auto" for the system's language, else a code), falling back to English when there is no catalog for it. */
    suspend fun load(choice: String) {
        val have = available()
        val code = pickLanguage(if (choice == "auto") systemLanguage() else choice, have)
        if (code == null) {
            table = emptyMap(); current = "en"; version++; return
        }
        val text = listOf("common.tsv", "wordcraft.tsv").mapNotNull { runCatching { Res.readBytes("files/i18n/$code/$it").decodeToString() }.getOrNull() }
        // The app's own rows over the shared ones.
        table = text.fold(emptyMap()) { acc, t -> acc + parseCatalog(t) }
        current = code
        version++
    }

    /** The name a language gives itself (its catalog's `language.name`), or its code. */
    suspend fun nameOf(code: String): String =
        runCatching { Res.readBytes("files/i18n/$code/common.tsv").decodeToString() }.getOrNull()?.let { parseCatalog(it)[contextKey("@id", "language.name")] } ?: code
}

/** [en] in the interface language, or [en] itself when it has no translation. [context] tells apart two meanings of the same English. */
fun tr(en: String, context: String = ""): String = I18n.table[if (context.isEmpty()) en else contextKey(context, en)] ?: en

/** [en] in the interface language with its `{name}` placeholders filled from [values]. */
fun trf(en: String, vararg values: Pair<String, Any>): String =
    values.fold(tr(en)) { acc, (k, v) -> acc.replace("{$k}", v.toString()) }

internal fun contextKey(context: String, en: String) = "$context\u0000$en"

/**
 * A craft-i18n catalog: lines of context, English and translation, separated by tabs (`\n`, `\t` and `\\` escaped), with
 * `#` comments and blank lines skipped. A row with a context is kept under that context; plural rows are left out (the
 * app has no counted strings yet).
 */
internal fun parseCatalog(text: String): Map<String, String> {
    val out = HashMap<String, String>()
    for (line in text.lineSequence()) {
        if (line.isBlank() || line.startsWith("#")) continue
        val cols = line.split('\t')
        if (cols.size < 3) continue
        val (ctx, en, tr) = Triple(cols[0], unescape(cols[1]), unescape(cols[2]))
        if (en.isEmpty() || tr.isEmpty() || ctx == "@plural") continue
        out[if (ctx.isEmpty()) en else contextKey(ctx, en)] = tr
    }
    return out
}

private fun unescape(s: String): String {
    if ('\\' !in s) return s
    val b = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) { 'n' -> b.append('\n'); 't' -> b.append('\t'); '\\' -> b.append('\\'); else -> { b.append(c); i--} }
            i += 2
        } else { b.append(c); i++ }
    }
    return b.toString()
}

/** The catalog for [tag] among [have]: the whole tag (`pt-br`), else its language (`pt`); null for English or none. */
internal fun pickLanguage(tag: String, have: List<String>): String? {
    val t = tag.lowercase().replace('_', '-')
    if (t == "en" || t.startsWith("en-")) return null
    return have.firstOrNull { it == t } ?: have.firstOrNull { it == t.substringBefore('-') }
}

/** File › Interface Language: the system's language, or one of the catalogs this build has, each by its own name. */
@androidx.compose.runtime.Composable
internal fun LanguageDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var langs by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<Pair<String, String>>?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { langs = I18n.available().map { it to I18n.nameOf(it) }.sortedBy { it.second.lowercase() } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(tr("Interface language")) },
        text = {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.androidx_verticalScroll()) {
                val choices = listOf("auto" to tr("Same as the system"), "en" to "English") + langs.orEmpty()
                for ((code, name) in choices) {
                    val here = AppSettings.value.language == code
                    androidx.compose.material3.TextButton(onClick = { onPick(code) }) {
                        androidx.compose.material3.Text((if (here) "✓ " else "") + name)
                    }
                }
                if (langs?.isEmpty() == true) androidx.compose.material3.Text(tr("This build has English only."))
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text(app.loki.kit.kt("Close")) } },
    )
}

@androidx.compose.runtime.Composable
private fun androidx.compose.ui.Modifier.androidx_verticalScroll(): androidx.compose.ui.Modifier =
    this.then(androidx.compose.ui.Modifier.heightIn(max = 480.dp)).verticalScroll(androidx.compose.foundation.rememberScrollState())
