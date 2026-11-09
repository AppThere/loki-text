package app.loki.text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the Home tab's own controls need: the engine's ribbon entries (for labels, shortcuts), what
 * is on and off, the formatting at the selection, the font families, and ways to run a command
 * with and without parameters.
 */
class RibbonEnv(
    val items: Map<String, RibbonItem>,
    val state: CommandState,
    val format: FormatState,
    val fonts: List<String>,
    val previews: Map<String, FontFamily>,
    val onRun: (String) -> Unit,
    val onRunWith: (String, JsonObject) -> Unit,
    /** The page tool that is on (`table.draw` or `table.eraser`), or null. */
    val tableTool: String? = null,
    /** Turns a page tool on, or all off with null. */
    val onTableTool: (String?) -> Unit = {},
    /** What the Equation tab offers: structure galleries and symbol sets. */
    val equation: EquationGallery = EquationGallery.EMPTY,
) {
    fun label(id: String, fallback: String) = items[id]?.label ?: fallback
    fun shortcut(id: String) = items[id]?.shortcut ?: ""
    fun reason(id: String) = state.disabled[id]
}

/** The groups of the Home tab that [HomeGroup] draws itself, instead of the generic button grid. */
val HOME_GROUPS = setOf("Clipboard", "Font", "Paragraph", "Editing")

/** Does [group] of [tab] have controls of its own, drawn by [CustomGroup]? */
fun hasCustomGroup(tab: String, group: String): Boolean = when (tab) {
    "Home" -> group in HOME_GROUPS
    "Layout" -> group in LAYOUT_GROUPS
    "Insert" -> group in INSERT_GROUPS
    "Table Design" -> group in TABLE_DESIGN_GROUPS
    "Table Layout" -> group in TABLE_LAYOUT_GROUPS
    "Picture Format" -> group in PICTURE_GROUPS
    "Shape Format" -> group in SHAPE_GROUPS
    "Equation" -> group in EQUATION_GROUPS
    else -> false
}

/** The group's own controls. */
@Composable
fun CustomGroup(tab: String, group: String, env: RibbonEnv, items: List<RibbonItem> = emptyList()) {
    when (tab) {
        "Layout" -> LayoutGroup(group, env, items)
        "Insert" -> InsertGroup(group, env)
        "Table Design" -> TableGroup(group, env)
        "Table Layout" -> TableLayoutGroup(group, items, env)
        "Picture Format" -> PictureGroup(group, env)
        "Shape Format" -> ShapeGroup(group, env)
        "Equation" -> EquationGroup(group, env)
        else -> HomeGroup(group, env)
    }
}

/** One command as a button; [size] is how it is drawn. */
@Composable
internal fun Cmd(env: RibbonEnv, id: String, fallback: String, size: ButtonSize, params: JsonObject? = null) {
    RibbonButton(
        iconId = id,
        label = env.label(id, fallback),
        size = size,
        shortcut = env.shortcut(id),
        disabledReason = env.reason(id),
        checked = id in env.state.on,
    ) { if (params == null) env.onRun(id) else env.onRunWith(id, params) }
}

/** The Home tab group called [name], with the real controls: font and size boxes, colours, spacing. */
@Composable
fun HomeGroup(name: String, env: RibbonEnv) {
    when (name) {
        "Clipboard" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // The engine arms the painter on one command and applies it on another; the first tap
            // copies the formatting, the second applies it to whatever is selected by then.
            var painting by remember { mutableStateOf(false) }
            Cmd(env, "edit.paste", "Paste", ButtonSize.Big)
            Column {
                Cmd(env, "edit.cut", "Cut", ButtonSize.Labeled)
                Cmd(env, "edit.copy", "Copy", ButtonSize.Labeled)
                RibbonButton(
                    "app.formatPainter",
                    if (painting) "Apply" else "Format",
                    ButtonSize.Labeled,
                    disabledReason = env.reason("edit.copyFormat"),
                    checked = painting,
                ) {
                    env.onRun(if (painting) "edit.pasteFormat" else "edit.copyFormat")
                    painting = !painting
                }
            }
        }
        // Three stacked controls: Find and Select open menus of related commands.
        "Editing" -> Column {
            val find = listOf("Find" to "edit.find", "Advanced Find…" to "edit.advancedFind", "Go To…" to "edit.goto")
            IconMenuButton("edit.find", env.label("edit.find", "Find"), find.map { it.first }, null, env.reason("edit.find"), ButtonSize.Labeled) { i ->
                find.getOrNull(i)?.second?.let(env.onRun)
            }
            Cmd(env, "edit.findReplace", "Replace", ButtonSize.Labeled)
            val select = listOf("Select All" to "select.all", "Select Paragraph" to "select.paragraph", "Select Sentence" to "select.sentence")
            IconMenuButton("select.all", "Select", select.map { it.first }, null, env.reason("select.all"), ButtonSize.Labeled) { i ->
                select.getOrNull(i)?.second?.let(env.onRun)
            }
        }
        "Font" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DropdownField(env.format.font.orEmpty(), 160.dp, env.fonts, "Font", env.previews) { f -> env.onRunWith("format.font", buildJsonObject { put("name", f) }) }
                SizeField(env.format.size) { s -> env.onRunWith("format.size", buildJsonObject { put("size", s) }) }
                Cmd(env, "format.growFont", "Increase Font Size", ButtonSize.Icon)
                Cmd(env, "format.shrinkFont", "Decrease Font Size", ButtonSize.Icon)
                Cmd(env, "format.clear", "Clear All Formatting", ButtonSize.Icon)
                val cases = listOf("Sentence case." to "sentence", "lowercase" to "lower", "UPPERCASE" to "upper", "Capitalize Each Word" to "title", "tOGGLE cASE" to "toggle")
                IconMenuButton("format.changeCase", env.label("format.changeCase", "Change Case"), cases.map { it.first }, null, env.reason("format.changeCase")) { i ->
                    val mode = cases.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("format.changeCase", buildJsonObject { put("mode", mode) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cmd(env, "format.bold", "Bold", ButtonSize.Icon)
                Cmd(env, "format.italic", "Italic", ButtonSize.Icon)
                Cmd(env, "format.underline", "Underline", ButtonSize.Icon)
                Cmd(env, "format.strikethrough", "Strikethrough", ButtonSize.Icon)
                Cmd(env, "format.subscript", "Subscript", ButtonSize.Icon)
                Cmd(env, "format.superscript", "Superscript", ButtonSize.Icon)
                Row(Modifier.padding(start = 6.dp)) {
                    ColorSplitButton("format.highlight", env.label("format.highlight", "Text Highlight Color"), HIGHLIGHTS, HIGHLIGHTS.first(), "No Color", env.reason("format.highlight")) { c ->
                        env.onRunWith("format.highlight", buildJsonObject { put("color", c ?: "None") })
                    }
                    ColorSplitButton("format.color", env.label("format.color", "Font Color"), FONT_COLORS, FONT_COLORS[4], "Automatic", env.reason("format.color"), custom = true) { c ->
                        env.onRunWith("format.color", buildJsonObject { put("color", c ?: "auto") })
                    }
                }
            }
        }
        "Paragraph" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val on = env.state.on
                val none = buildJsonObject { put("off", true) }
                fun kind(k: String) = buildJsonObject { put("kind", k) }
                SplitMenuButton(
                    "para.bullets", env.label("para.bullets", "Bullets"), "para.bullets" in on, env.reason("para.bullets"),
                    listOf(MenuChoice("Bullet Library")) + BULLET_GLYPHS.map { MenuChoice(it, "para.bullets", kind(it)) } + MenuChoice("None", "para.bullets", none) + MenuChoice("Define New Bullet…", "para.defineBullet"),
                    onClick = { env.onRun("para.bullets") },
                ) { c -> env.onRunWith(c.command, c.params) }
                SplitMenuButton(
                    "para.numbering", env.label("para.numbering", "Numbering"), "para.numbering" in on, env.reason("para.numbering"),
                    NUMBERINGS.map { (l, k) -> MenuChoice(l, "para.numbering", kind(k)) } +
                        listOf(MenuChoice("Restart at 1", "para.restartNumbering"), MenuChoice("Set Numbering Value…", "para.setNumberingValue"), MenuChoice("Define New Number Format…", "para.defineNumber"), MenuChoice("None", "para.numbering", none)),
                    onClick = { env.onRun("para.numbering") },
                ) { c -> env.onRunWith(c.command, c.params) }
                SplitMenuButton(
                    "para.multilevel", env.label("para.multilevel", "Multilevel List"), "para.multilevel" in on, env.reason("para.multilevel"),
                    listOf(MenuChoice("1.  1.1.  1.1.1.", "para.multilevel", kind("legal")), MenuChoice("I.  A.  1.  a.", "para.multilevel", kind("outline")), MenuChoice("Linked to headings")) +
                        MULTILEVEL_PRESETS.map { (l, p) -> MenuChoice(l, "list.multilevelPreset", buildJsonObject { put("preset", p) }) } + MenuChoice("Define New Multilevel List…", "list.define"),
                    onClick = { env.onRun("para.multilevel") },
                ) { c -> env.onRunWith(c.command, c.params) }
                Row(Modifier.padding(start = 6.dp)) {
                    Cmd(env, "para.outdent", "Decrease Indent", ButtonSize.Icon)
                    Cmd(env, "para.indent", "Increase Indent", ButtonSize.Icon)
                }
                Row(Modifier.padding(start = 6.dp)) {
                    IconMenuButton("para.sort", env.label("para.sort", "Sort"), listOf("A to Z", "Z to A"), null, env.reason("para.sort")) { i ->
                        env.onRunWith("para.sort", buildJsonObject { put("descending", i == 1) })
                    }
                    Cmd(env, "view.marks", "Show/Hide ¶", ButtonSize.Icon)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cmd(env, "para.alignLeft", "Align Left", ButtonSize.Icon)
                Cmd(env, "para.alignCenter", "Center", ButtonSize.Icon)
                Cmd(env, "para.alignRight", "Align Right", ButtonSize.Icon)
                Cmd(env, "para.justify", "Justify", ButtonSize.Icon)
                Row(Modifier.padding(start = 6.dp)) {
                    IconMenuButton(
                        "para.lineSpacing", env.label("para.lineSpacing", "Line and Paragraph Spacing"),
                        LINE_SPACINGS.map { spacingText(it) }, LINE_SPACINGS.firstOrNull { sameNumber(env.state.layout.lineSpacing, it) }?.let { spacingText(it) }, env.reason("para.lineSpacing"),
                    ) { i ->
                        val v = LINE_SPACINGS.getOrNull(i) ?: return@IconMenuButton
                        env.onRunWith("para.lineSpacing", buildJsonObject { put("value", v) })
                    }
                    BordersButton(env)
                    ColorSplitButton("para.shading", env.label("para.shading", "Shading"), SHADINGS, SHADINGS.first(), "No Color", env.reason("para.shading"), custom = true) { c ->
                        env.onRunWith("para.shading", buildJsonObject { if (c == null) put("color", JsonNull) else put("color", c) })
                    }
                }
            }
        }
    }
}

/**
 * Borders: a panel to choose the line style and colour (they stay chosen), then the sides to draw
 * them on. Picking a side applies the border and closes the panel.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
private fun BordersButton(env: RibbonEnv) {
    var open by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf("single") }
    var color by remember { mutableStateOf<Swatch?>(null) }
    val label = env.label("para.borders", "Borders")
    Box {
        RibbonButton("para.borders", label, ButtonSize.Icon, disabledReason = env.reason("para.borders")) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val head = MaterialTheme.typography.labelSmall
            val dim = MaterialTheme.colorScheme.outline
            Text("Line style", style = head, color = dim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            FlowRow(Modifier.padding(horizontal = 8.dp), maxItemsInEachRow = 3) {
                for ((name, key) in BORDER_STYLES) {
                    FilterChip(
                        selected = style == key, onClick = { style = key },
                        label = { Text(name, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
            Text("Line color", style = head, color = dim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            FlowRow(Modifier.padding(horizontal = 8.dp), maxItemsInEachRow = 6) {
                for (sw in FONT_COLORS) {
                    val picked = color?.param == sw.param
                    Box(
                        Modifier.padding(2.dp).size(26.dp)
                            .background(sw.color, RoundedCornerShape(4.dp))
                            .border(if (picked) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                            .clickable { color = sw },
                    )
                }
            }
            Text("Apply to", style = head, color = dim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            for ((name, kind) in BORDER_KINDS) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable {
                        open = false
                        env.onRunWith("para.borders", buildJsonObject {
                            put("kind", kind)
                            if (kind != "none") {
                                put("style", style)
                                color?.let { put("color", it.param) }
                            }
                        })
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
