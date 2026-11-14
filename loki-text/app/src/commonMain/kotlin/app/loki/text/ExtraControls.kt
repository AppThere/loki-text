package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Groups that keep their generic buttons and add controls for commands that take a value, as
 * (tab, group). Unlike the groups in [hasCustomGroup], nothing already in the group is replaced.
 */
private val EXTRA_GROUPS = setOf(
    "Review" to "Comments", "Review" to "Speech", "References" to "Table of Contents",
    "Outlining" to "Outline Tools", "Chart Design" to "Type", "Chart Design" to "Chart Layouts",
    "SmartArt Design" to "Layouts", "SmartArt Design" to "SmartArt Styles",
)

/** Does [group] of [tab] get [GroupExtras] beside its generic buttons? */
fun hasGroupExtras(tab: String, group: String): Boolean = (tab to group) in EXTRA_GROUPS || (tab to group) in TABLE_EXTRA_GROUPS

/** Reading speeds: label, and the multiple `readAloud.speed` takes. */
internal val READING_SPEEDS = listOf("0.5×" to 0.5, "0.75×" to 0.75, "Normal" to 1.0, "1.25×" to 1.25, "1.5×" to 1.5, "2×" to 2.0)

/** Table of contents depths: label, and the `levels` value of `references.toc`. */
internal val TOC_LEVELS = listOf("Heading 1 only" to 1, "Headings 1–2" to 2, "Headings 1–3" to 3, "Headings 1–4" to 4, "Headings 1–5" to 5, "Headings 1–9" to 9)

/** Outline levels: label, and the `level` of `outline.level` (a number, or "body"). */
internal val OUTLINE_LEVELS: List<Pair<String, String>> = (1..9).map { "Level $it" to "$it" } + ("Body Text" to "body")

/** Show Level: label, and the `level` of `outline.showLevel` (1–9 or "all"). */
internal val SHOW_LEVELS: List<Pair<String, String>> = (1..9).map { "Show Level $it" to "$it" } + ("Show All Levels" to "all")

/** Chart types: label, and the engine's name. */
internal val CHART_TYPES = listOf(
    "Column" to "column", "Stacked Column" to "columnStacked", "Bar" to "bar", "Stacked Bar" to "barStacked",
    "Line" to "line", "Area" to "area", "Pie" to "pie", "Doughnut" to "doughnut", "Scatter" to "scatter",
)

/** Legend places: label, and the engine's name. */
internal val LEGEND_POSITIONS = listOf("Right" to "right", "Left" to "left", "Top" to "top", "Bottom" to "bottom", "None" to "none")

/** SmartArt layouts and colour variants: label, and the engine's name. */
internal val SMART_ART_LAYOUTS = listOf(
    "Basic List" to "basicList", "Process" to "process", "Cycle" to "cycle", "Hierarchy" to "hierarchy",
    "Pyramid" to "pyramid", "Radial" to "radial", "Matrix" to "matrix", "Venn" to "venn",
)
internal val SMART_ART_COLORS = listOf(
    "Accent 1" to "accent1", "Accent 2" to "accent2", "Accent 3" to "accent3", "Accent 4" to "accent4",
    "Accent 5" to "accent5", "Accent 6" to "accent6", "Colorful" to "colorful",
)

/** The `level` value as the engine takes it: a number for 1–9, else the word. */
internal fun levelParams(level: String): JsonObject = buildJsonObject {
    val n = level.toIntOrNull()
    if (n != null) put("level", n) else put("level", level)
}

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
            "Outlining" to "Outline Tools" -> Column {
                IconMenuButton("outline.level", env.label("outline.level", "Outline Level"), OUTLINE_LEVELS.map { it.first }, null, env.reason("outline.level"), ButtonSize.Labeled) { i ->
                    val l = OUTLINE_LEVELS.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("outline.level", levelParams(l))
                }
                IconMenuButton("outline.showLevel", env.label("outline.showLevel", "Show Level"), SHOW_LEVELS.map { it.first }, null, env.reason("outline.showLevel"), ButtonSize.Labeled) { i ->
                    val l = SHOW_LEVELS.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("outline.showLevel", levelParams(l))
                }
            }
            "Chart Design" to "Type" -> IconMenuButton("chart.type", env.label("chart.type", "Change Chart Type"), CHART_TYPES.map { it.first }, null, env.reason("chart.type"), ButtonSize.Big) { i ->
                val t = CHART_TYPES.getOrNull(i)?.second ?: return@IconMenuButton
                env.onRunWith("chart.type", buildJsonObject { put("type", t) })
            }
            "Chart Design" to "Chart Layouts" -> Column {
                IconMenuButton("chart.title", env.label("chart.title", "Chart Title"), listOf("Set Title…", "No Title"), null, env.reason("chart.title"), ButtonSize.Labeled) { i ->
                    if (i == 0) dialog = InsertDialog.ChartTitle else env.onRunWith("chart.title", buildJsonObject { put("text", JsonNull) })
                }
                IconMenuButton("chart.legend", env.label("chart.legend", "Legend"), LEGEND_POSITIONS.map { it.first }, null, env.reason("chart.legend"), ButtonSize.Labeled) { i ->
                    val pos = LEGEND_POSITIONS.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("chart.legend", buildJsonObject { put("pos", pos) })
                }
            }
            "SmartArt Design" to "Layouts" -> IconMenuButton("smartArt.layout", env.label("smartArt.layout", "Change Layout"), SMART_ART_LAYOUTS.map { it.first }, null, env.reason("smartArt.layout"), ButtonSize.Big) { i ->
                val l = SMART_ART_LAYOUTS.getOrNull(i)?.second ?: return@IconMenuButton
                env.onRunWith("smartArt.layout", buildJsonObject { put("layout", l) })
            }
            "SmartArt Design" to "SmartArt Styles" -> IconMenuButton("smartArt.colors", env.label("smartArt.colors", "Change Colors"), SMART_ART_COLORS.map { it.first }, null, env.reason("smartArt.colors"), ButtonSize.Big) { i ->
                val v = SMART_ART_COLORS.getOrNull(i)?.second ?: return@IconMenuButton
                env.onRunWith("smartArt.colors", buildJsonObject { put("variant", v) })
            }
            "References" to "Table of Contents" -> IconMenuButton("references.toc", env.label("references.toc", "Table of Contents"), TOC_LEVELS.map { it.first }, null, env.reason("references.toc"), ButtonSize.Big) { i ->
                val n = TOC_LEVELS.getOrNull(i)?.second ?: return@IconMenuButton
                env.onRunWith("references.toc", buildJsonObject { put("levels", n) })
            }
            else -> if ((tab to group) in TABLE_EXTRA_GROUPS) TableExtras(tab, group, env)
        }
    }
}
