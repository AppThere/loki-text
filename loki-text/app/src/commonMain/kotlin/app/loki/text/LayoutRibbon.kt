package app.loki.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Layout tab groups that [LayoutGroup] draws itself. */
val LAYOUT_GROUPS = setOf("Page Setup", "Paragraph")

private val MARGIN_PRESETS = listOf(
    "Normal" to "normal", "Narrow" to "narrow", "Moderate" to "moderate", "Wide" to "wide", "Mirrored" to "mirrored", "Office 2003 Default" to "office2003",
)
private val PAPERS = listOf("Letter", "Legal", "Executive", "A3", "A4", "A5")

/** Column layouts: label, and the parameters `layout.columns` takes. */
private val COLUMN_CHOICES: List<Pair<String, JsonObject>> = listOf(
    "One" to buildJsonObject { put("count", 1) },
    "Two" to buildJsonObject { put("count", 2) },
    "Three" to buildJsonObject { put("count", 3) },
    "Left (narrow, wide)" to buildJsonObject { put("count", 2); put("preset", "left") },
    "Right (wide, narrow)" to buildJsonObject { put("count", 2); put("preset", "right") },
)

/** Indents in points, labelled in inches. */
private val INDENTS = listOf("0" to 0f, "0.25 in" to 18f, "0.5 in" to 36f, "1 in" to 72f, "1.5 in" to 108f)

/** Paragraph spacing in points. */
private val SPACINGS = listOf(0f, 6f, 8f, 12f, 18f, 24f)

/** The label in [choices] whose value matches, to mark it in the menu. */
private fun <T> markedLabel(choices: List<Pair<String, T>>, matches: (T) -> Boolean): String? = choices.firstOrNull { matches(it.second) }?.first

private val COLUMN_NAMES = mapOf("one" to "One", "two" to "Two", "three" to "Three", "left" to "Left (narrow, wide)", "right" to "Right (wide, narrow)")

/** The Layout tab's Page Setup and Paragraph groups, with menus for the settings that take a value. */
@Composable
fun LayoutGroup(name: String, env: RibbonEnv) {
    val lay = env.state.layout
    when (name) {
        "Page Setup" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconMenuButton("layout.margins", env.label("layout.margins", "Margins"), MARGIN_PRESETS.map { it.first }, markedLabel(MARGIN_PRESETS) { it == lay.margins }, env.reason("layout.margins"), ButtonSize.Big) { i ->
                val preset = MARGIN_PRESETS.getOrNull(i)?.second ?: return@IconMenuButton
                env.onRunWith("layout.margins", buildJsonObject { put("preset", preset) })
            }
            IconMenuButton("layout.orientation", env.label("layout.orientation", "Orientation"), listOf("Portrait", "Landscape"), lay.landscape?.let { if (it) "Landscape" else "Portrait" }, env.reason("layout.orientation"), ButtonSize.Big) { i ->
                env.onRunWith("layout.orientation", buildJsonObject { put("value", if (i == 1) "landscape" else "portrait") })
            }
            IconMenuButton("layout.size", env.label("layout.size", "Size"), PAPERS, lay.paper, env.reason("layout.size"), ButtonSize.Big) { i ->
                val paper = PAPERS.getOrNull(i) ?: return@IconMenuButton
                env.onRunWith("layout.size", buildJsonObject { put("name", paper) })
            }
            IconMenuButton("layout.columns", env.label("layout.columns", "Columns"), COLUMN_CHOICES.map { it.first }, COLUMN_NAMES[lay.columns], env.reason("layout.columns"), ButtonSize.Big) { i ->
                val params = COLUMN_CHOICES.getOrNull(i)?.second ?: return@IconMenuButton
                env.onRunWith("layout.columns", params)
            }
            Column {
                for ((id, fallback) in listOf("layout.lineNumbers" to "Line Numbers", "layout.pageSetup" to "Page Setup")) {
                    RibbonButton(id, env.label(id, fallback), ButtonSize.Labeled, env.shortcut(id), env.reason(id), id in env.state.on) { env.onRun(id) }
                }
            }
        }
        "Paragraph" -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconMenuButton("app.indentLeft", "Indent Left", INDENTS.map { it.first }, markedLabel(INDENTS) { sameNumber(lay.indentLeft, it) }, env.reason("para.indents"), ButtonSize.Labeled) { i ->
                    val v = INDENTS.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("para.indents", buildJsonObject { put("left", v) })
                }
                IconMenuButton("app.indentRight", "Indent Right", INDENTS.map { it.first }, markedLabel(INDENTS) { sameNumber(lay.indentRight, it) }, env.reason("para.indents"), ButtonSize.Labeled) { i ->
                    val v = INDENTS.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("para.indents", buildJsonObject { put("right", v) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconMenuButton("app.spaceBefore", "Space Before", SPACINGS.map { "${spacingText(it)} pt" }, SPACINGS.firstOrNull { sameNumber(lay.spaceBefore, it) }?.let { "${spacingText(it)} pt" }, env.reason("para.spacing"), ButtonSize.Labeled) { i ->
                    val v = SPACINGS.getOrNull(i) ?: return@IconMenuButton
                    env.onRunWith("para.spacing", buildJsonObject { put("before", v) })
                }
                IconMenuButton("app.spaceAfter", "Space After", SPACINGS.map { "${spacingText(it)} pt" }, SPACINGS.firstOrNull { sameNumber(lay.spaceAfter, it) }?.let { "${spacingText(it)} pt" }, env.reason("para.spacing"), ButtonSize.Labeled) { i ->
                    val v = SPACINGS.getOrNull(i) ?: return@IconMenuButton
                    env.onRunWith("para.spacing", buildJsonObject { put("after", v) })
                }
            }
        }
    }
}
