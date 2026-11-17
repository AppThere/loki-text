package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One structure the gallery can insert (a stacked fraction, a radical, …). */
class EquationTemplate(val id: String, val label: String, val linear: String)

/** A structure gallery: one button on the Equation tab (Fraction, Script, Radical, …) and its templates. */
class EquationStructure(val id: String, val label: String, val templates: List<EquationTemplate>)

/** A category of symbols (Basic Math, Greek Letters, Arrows, …). */
class SymbolSet(val name: String, val chars: List<String>)

/** What the engine offers for the Equation tab, from `equation.gallery`. */
class EquationGallery(val structures: List<EquationStructure>, val symbols: List<SymbolSet>) {
    companion object {
        val EMPTY = EquationGallery(emptyList(), emptyList())
    }
}

/** The most symbols a set may list and structures a gallery may hold: the reply is not trusted. */
private const val MAX_ITEMS = 2000

/** Parses `equation.gallery`; anything malformed is skipped, and a bad reply gives [EquationGallery.EMPTY]. */
fun parseEquationGallery(reply: JsonElement): EquationGallery {
    val o = reply as? JsonObject ?: return EquationGallery.EMPTY
    fun JsonElement?.str(k: String) = (this as? JsonObject)?.get(k)?.jsonPrimitive?.contentOrNull
    val structures = (o["structures"] as? JsonArray).orEmpty().take(MAX_ITEMS).mapNotNull { g ->
        val id = g.str("id") ?: return@mapNotNull null
        val templates = ((g as? JsonObject)?.get("sections") as? JsonArray).orEmpty().flatMap { sec ->
            ((sec as? JsonObject)?.get("templates") as? JsonArray).orEmpty().mapNotNull { t ->
                EquationTemplate(t.str("id") ?: return@mapNotNull null, t.str("label") ?: return@mapNotNull null, t.str("linear").orEmpty())
            }
        }.take(MAX_ITEMS)
        if (templates.isEmpty()) null else EquationStructure(id, g.str("label") ?: id, templates)
    }
    val symbols = (o["symbols"] as? JsonArray).orEmpty().take(MAX_ITEMS).mapNotNull { s ->
        val name = s.str("name") ?: return@mapNotNull null
        // One symbol per code point (a string of them in the reply), so a character outside the BMP stays whole.
        val chars = splitCodePoints(s.str("chars").orEmpty()).take(MAX_ITEMS)
        if (chars.isEmpty()) null else SymbolSet(name, chars)
    }
    return EquationGallery(structures, symbols)
}

/** [s] as one string per code point, so a symbol outside the BMP (a surrogate pair) stays whole. */
internal fun splitCodePoints(s: String): List<String> {
    val out = ArrayList<String>(s.length)
    var i = 0
    while (i < s.length) {
        val pair = s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()
        val n = if (pair) 2 else 1
        out.add(s.substring(i, i + n))
        i += n
    }
    return out
}

/** The Equation tab groups that [EquationGroup] draws itself. */
val EQUATION_GROUPS = setOf("Structures", "Symbols", "Conversions")

private val CONVERSIONS = listOf("Professional" to "professional", "Linear" to "linear")
private val INPUT_FORMATS = listOf("Unicode" to "unicode", "LaTeX" to "latex")

/** The Equation tab group called [name]. */
@Composable
fun EquationGroup(name: String, env: RibbonEnv) {
    val gallery = env.equation
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        when (name) {
            "Structures" -> for (g in gallery.structures) {
                IconMenuButton("equation.insertStructure", g.label, g.templates.map { it.label }, null, env.reason("equation.insertStructure"), ButtonSize.Labeled) { i ->
                    val t = g.templates.getOrNull(i) ?: return@IconMenuButton
                    env.onRunWith("equation.insertStructure", buildJsonObject { put("id", t.id) })
                }
            }
            "Symbols" -> SymbolPicker(gallery.symbols, env)
            "Conversions" -> Column {
                IconMenuButton("equation.convert", env.label("equation.convert", "Convert"), CONVERSIONS.map { it.first }, null, env.reason("equation.convert"), ButtonSize.Labeled) { i ->
                    val to = CONVERSIONS.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("equation.convert", buildJsonObject { put("to", to) })
                }
                IconMenuButton("equation.inputFormat", env.label("equation.inputFormat", "Input Format"), INPUT_FORMATS.map { it.first }, null, env.reason("equation.inputFormat"), ButtonSize.Labeled) { i ->
                    val f = INPUT_FORMATS.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("equation.inputFormat", buildJsonObject { put("format", f) })
                }
            }
        }
    }
}

/** The symbols: a category row, and the selected category's symbols as a grid; a tap inserts one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolPicker(sets: List<SymbolSet>, env: RibbonEnv) {
    var open by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(0) }
    val set = sets.getOrNull(selected.coerceIn(0, (sets.size - 1).coerceAtLeast(0)))
    Box {
        RibbonButton("equation.insertSymbol", env.label("equation.insertSymbol", "Symbols"), ButtonSize.Big, disabledReason = env.reason("equation.insertSymbol") ?: if (set == null) "No symbols" else null) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FlowRow(Modifier.widthIn(max = 320.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                sets.forEachIndexed { i, s -> FilterChip(i == selected, { selected = i }, label = { Text(s.name, style = MaterialTheme.typography.labelSmall) }) }
            }
            FlowRow(Modifier.widthIn(max = 320.dp).fillMaxWidth().padding(8.dp)) {
                for (c in set?.chars.orEmpty()) {
                    Text(
                        c,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable { open = false; env.onRunWith("equation.insertSymbol", buildJsonObject { put("char", c) }) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
