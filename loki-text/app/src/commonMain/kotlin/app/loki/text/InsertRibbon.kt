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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Insert tab groups that [InsertGroup] draws itself: the commands in them need a value. */
val INSERT_GROUPS = setOf("Tables", "Header & Footer", "Symbols", "Illustrations", "Links", "Text")

/** Table sizes: label, rows, columns. */
private val TABLE_SIZES = listOf("2 × 2" to (2 to 2), "3 × 3" to (3 to 3), "4 × 3" to (4 to 3), "5 × 4" to (5 to 4), "8 × 5" to (8 to 5), "10 × 6" to (10 to 6))

/** Header and footer designs: label, and the engine's preset. */
private val HF_PRESETS = listOf("Blank" to "blank", "Blank (Three Columns)" to "blankThree", "Title" to "title")

/** Page number places: label, position, alignment. */
private val PAGE_NUMBERS = listOf(
    "Top, left" to ("top" to "left"), "Top, centre" to ("top" to "center"), "Top, right" to ("top" to "right"),
    "Bottom, left" to ("bottom" to "left"), "Bottom, centre" to ("bottom" to "center"), "Bottom, right" to ("bottom" to "right"),
)

/** Symbols people reach for most. */
private val SYMBOLS = listOf("©", "®", "™", "°", "±", "×", "÷", "≠", "≤", "≥", "€", "£", "¥", "§", "¶", "•", "→", "←", "✓", "…")

/** Shapes the engine draws: label, kind. */
private val SHAPES = listOf(
    "Rectangle" to "rectangle", "Rounded Rectangle" to "roundedRectangle", "Ellipse" to "ellipse", "Triangle" to "triangle",
    "Diamond" to "diamond", "Line" to "line", "Arrow" to "arrow", "Star" to "star", "Heart" to "heart",
)

/** Runs [id] with [params] from menu item [i] of [choices], ignoring an index that is out of range. */
private fun <T> RibbonEnv.pick(id: String, choices: List<T>, i: Int, params: (T) -> JsonObject) {
    val c = choices.getOrNull(i) ?: return
    onRunWith(id, params(c))
}

/** The Insert tab's groups with value-taking commands: menus supply the value the engine needs. */
@Composable
fun InsertGroup(name: String, env: RibbonEnv) {
    var dialog by remember { mutableStateOf<InsertDialog?>(null) }
    dialog?.let { d -> InsertDialogView(d, { p -> dialog = null; env.onRunWith(d.command, p) }, { dialog = null }) }
    // A button that opens [d]'s dialog.
    @Composable
    fun Ask(d: InsertDialog, fallback: String) =
        RibbonButton(d.command, env.label(d.command, fallback), ButtonSize.Big, env.shortcut(d.command), env.reason(d.command)) { dialog = d }
    @Composable
    fun Plain(id: String, fallback: String) =
        RibbonButton(id, env.label(id, fallback), ButtonSize.Labeled, env.shortcut(id), env.reason(id)) { env.onRun(id) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        when (name) {
            "Tables" -> {
                IconMenuButton("insert.table", env.label("insert.table", "Table"), TABLE_SIZES.map { it.first }, null, env.reason("insert.table"), ButtonSize.Big) { i ->
                    env.pick("insert.table", TABLE_SIZES, i) { (_, rc) -> buildJsonObject { put("rows", rc.first); put("cols", rc.second) } }
                }
                TableToolButton(env, "table.draw", "Draw Table")
            }
            "Header & Footer" -> {
                for ((id, fallback) in listOf("insert.header" to "Header", "insert.footer" to "Footer")) {
                    IconMenuButton(id, env.label(id, fallback), HF_PRESETS.map { it.first }, null, env.reason(id), ButtonSize.Big) { i ->
                        env.pick(id, HF_PRESETS, i) { (_, preset) -> buildJsonObject { put("preset", preset) } }
                    }
                }
                IconMenuButton("insert.pageNumber", env.label("insert.pageNumber", "Page Number"), PAGE_NUMBERS.map { it.first }, null, env.reason("insert.pageNumber"), ButtonSize.Big) { i ->
                    env.pick("insert.pageNumber", PAGE_NUMBERS, i) { (_, pa) -> buildJsonObject { put("position", pa.first); put("align", pa.second) } }
                }
            }
            "Symbols" -> IconMenuButton("insert.symbol", env.label("insert.symbol", "Symbol"), SYMBOLS, null, env.reason("insert.symbol"), ButtonSize.Big) { i ->
                env.pick("insert.symbol", SYMBOLS, i) { ch -> buildJsonObject { put("char", ch) } }
            }
            "Links" -> {
                Ask(InsertDialog.Link, "Link")
                Ask(InsertDialog.Bookmark, "Bookmark")
                Plain("insert.removeLink", "Remove Hyperlink")
            }
            "Text" -> {
                Ask(InsertDialog.TextBox, "Text Box")
                Ask(InsertDialog.DateTime, "Date & Time")
                Plain("insert.dropCap", "Drop Cap")
            }
            "Illustrations" -> {
                Ask(InsertDialog.Picture, "Pictures")
                IconMenuButton("insert.shape", env.label("insert.shape", "Shapes"), SHAPES.map { it.first }, null, env.reason("insert.shape"), ButtonSize.Big) { i ->
                env.pick("insert.shape", SHAPES, i) { (_, kind) -> buildJsonObject { put("kind", kind) } }
                }
            }
        }
    }
}
