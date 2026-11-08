package app.loki.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Groups that keep their generic buttons and add controls for commands that take a value, as
 * (tab, group). Unlike the groups in [hasCustomGroup], nothing already in the group is replaced.
 */
private val EXTRA_GROUPS = setOf("Review" to "Comments", "Review" to "Speech", "References" to "Table of Contents")

/** Does [group] of [tab] get [GroupExtras] beside its generic buttons? */
fun hasGroupExtras(tab: String, group: String): Boolean = (tab to group) in EXTRA_GROUPS || (tab to group) in TABLE_EXTRA_GROUPS

/** Reading speeds: label, and the multiple `readAloud.speed` takes. */
internal val READING_SPEEDS = listOf("0.5×" to 0.5, "0.75×" to 0.75, "Normal" to 1.0, "1.25×" to 1.25, "1.5×" to 1.5, "2×" to 2.0)

/** Table of contents depths: label, and the `levels` value of `references.toc`. */
internal val TOC_LEVELS = listOf("Heading 1 only" to 1, "Headings 1–2" to 2, "Headings 1–3" to 3, "Headings 1–4" to 4, "Headings 1–5" to 5, "Headings 1–9" to 9)

/** The extra controls of [group] in [tab]. */
@Composable
fun GroupExtras(tab: String, group: String, env: RibbonEnv) {
    var dialog by remember { mutableStateOf<InsertDialog?>(null) }
    dialog?.let { d -> InsertDialogView(d, { p -> dialog = null; env.onRunWith(d.command, p) }, { dialog = null }) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        when (tab to group) {
            "Review" to "Comments" -> RibbonButton("review.newComment", env.label("review.newComment", "New Comment"), ButtonSize.Big, env.shortcut("review.newComment"), env.reason("review.newComment")) {
                dialog = InsertDialog.NewComment
            }
            "Review" to "Speech" -> IconMenuButton("readAloud.speed", env.label("readAloud.speed", "Reading Speed"), READING_SPEEDS.map { it.first }, null, env.reason("readAloud.speed"), ButtonSize.Big) { i ->
                val v = READING_SPEEDS.getOrNull(i)?.second ?: return@IconMenuButton
                env.onRunWith("readAloud.speed", buildJsonObject { put("value", v) })
            }
            "References" to "Table of Contents" -> IconMenuButton("references.toc", env.label("references.toc", "Table of Contents"), TOC_LEVELS.map { it.first }, null, env.reason("references.toc"), ButtonSize.Big) { i ->
                val n = TOC_LEVELS.getOrNull(i)?.second ?: return@IconMenuButton
                env.onRunWith("references.toc", buildJsonObject { put("levels", n) })
            }
            else -> if ((tab to group) in TABLE_EXTRA_GROUPS) TableExtras(tab, group, env)
        }
    }
}
