package app.loki.text

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A run of Unicode the Symbol dialog lists: its name and first and last code points. */
internal class SymbolSubset(val name: String, val first: Int, val last: Int)

/** Subsets whose code points are nearly all assigned, so the grid has few empty squares. */
internal val SYMBOL_SUBSETS = listOf(
    SymbolSubset("Latin-1 Supplement", 0xA1, 0xFF),
    SymbolSubset("Latin Extended-A", 0x100, 0x17F),
    SymbolSubset("Greek and Coptic", 0x391, 0x3C9),
    SymbolSubset("Cyrillic", 0x400, 0x45F),
    SymbolSubset("General Punctuation", 0x2010, 0x2027),
    SymbolSubset("Superscripts and Subscripts", 0x2070, 0x209C),
    SymbolSubset("Currency Symbols", 0x20A0, 0x20C0),
    SymbolSubset("Letterlike Symbols", 0x2100, 0x214F),
    SymbolSubset("Number Forms", 0x2150, 0x2189),
    SymbolSubset("Arrows", 0x2190, 0x21FF),
    SymbolSubset("Mathematical Operators", 0x2200, 0x22FF),
    SymbolSubset("Miscellaneous Technical", 0x2300, 0x23FF),
    SymbolSubset("Box Drawing", 0x2500, 0x257F),
    SymbolSubset("Geometric Shapes", 0x25A0, 0x25FF),
    SymbolSubset("Miscellaneous Symbols", 0x2600, 0x26FF),
    SymbolSubset("Dingbats", 0x2701, 0x27BF),
)

/** The code points of [s] that are characters (the surrogate and private-use areas are not listed). */
internal fun subsetCodePoints(s: SymbolSubset): List<Int> = (s.first..s.last).filter { it !in 0xD800..0xF8FF && it != 0xAD }

/** [cp] as a string, with a surrogate pair above the Basic Multilingual Plane; null for what is not a character. */
internal fun codePointText(cp: Int): String? {
    if (cp < 0x20 || cp in 0x7F..0x9F || cp in 0xD800..0xDFFF || cp > 0x10FFFF) return null
    if (cp < 0x10000) return cp.toChar().toString()
    val v = cp - 0x10000
    return charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
}

/** A character code as typed (`00A9`, `U+00A9`, `0x2014`) to its code point; null for one that is not a character. */
internal fun parseCharCode(text: String): Int? {
    val t = text.trim().uppercase().removePrefix("U+").removePrefix("0X")
    if (t.isEmpty() || t.length > 6) return null
    return t.toIntOrNull(16)?.takeIf { codePointText(it) != null }
}

/** `U+00A9` for a code point. */
internal fun charCodeLabel(cp: Int): String = "U+" + cp.toString(16).uppercase().padStart(4, '0')

/** The first code point of [s], which is one character. */
internal fun firstCodePoint(s: String): Int? {
    val c = s.firstOrNull() ?: return null
    if (c.isHighSurrogate() && s.length > 1 && s[1].isLowSurrogate()) return 0x10000 + ((c.code - 0xD800) shl 10) + (s[1].code - 0xDC00)
    return c.code
}

internal fun symbolParams(cp: Int, font: String): JsonObject? {
    val ch = codePointText(cp) ?: return null
    return buildJsonObject { put("char", ch); font.trim().takeIf { it.isNotEmpty() }?.let { put("font", it) } }
}

/**
 * Insert › Symbol › More Symbols: every character of a subset in a grid, a box to type a character
 * code, the symbols used lately, and the font the symbol is written in (blank: the text's). The grid
 * is drawn in the interface's font, so a symbol font's own pictures show only on the page. Insert
 * leaves the dialog open, as people often insert several.
 */
@Composable
internal fun SymbolDialog(recent: List<String>, fonts: List<String>, onInsert: (JsonObject, String) -> Unit, onDismiss: () -> Unit) {
    var subset by remember { mutableStateOf(SYMBOL_SUBSETS.first()) }
    var picked by remember { mutableStateOf(recent.firstOrNull()?.let(::firstCodePoint) ?: subsetCodePoints(subset).first()) }
    var code by remember { mutableStateOf<String?>(null) }
    var font by remember { mutableStateOf("") }
    val cells = remember(subset) { subsetCodePoints(subset) }
    val codeBad = code?.let { parseCharCode(it) == null } == true
    @Composable
    fun Cell(cp: Int) {
        val text = codePointText(cp) ?: return
        val on = cp == picked
        Box(
            Modifier.size(40.dp)
                .border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                .semantics { contentDescription = "$text ${charCodeLabel(cp)}"; selected = on }
                .clickable(role = Role.Button) { picked = cp; code = null },
            contentAlignment = Alignment.Center,
        ) { Text(text, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Symbol")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Subset"), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in SYMBOL_SUBSETS) FilterChip(s == subset, { subset = s }, label = { Text(tr(s.name)) })
                }
                LazyVerticalGrid(GridCells.Adaptive(42.dp), Modifier.fillMaxWidth().height(200.dp)) {
                    items(cells) { Cell(it) }
                }
                if (recent.isNotEmpty()) {
                    Text(tr("Recently used symbols"), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (r in recent) firstCodePoint(r)?.let { Cell(it) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(codePointText(picked).orEmpty(), fontSize = 32.sp, color = MaterialTheme.colorScheme.onSurface)
                    OutlinedTextField(
                        code ?: charCodeLabel(picked).removePrefix("U+"),
                        { t -> code = t; parseCharCode(t)?.let { picked = it } },
                        label = { Text(tr("Character code (hex)")) }, singleLine = true, isError = codeBad, modifier = Modifier.weight(1f),
                    )
                }
                FontBox(tr("Font (blank: the text's)"), font, fonts, listHeight = 80.dp) { font = it }
            }
        },
        confirmButton = {
            TextButton(enabled = !codeBad, onClick = { symbolParams(picked, font)?.let { p -> onInsert(p, codePointText(picked).orEmpty()) } }) { Text(tr("Insert")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
    )
}

/** [recent] with [symbol] moved to the front, at most [max] long. */
internal fun withRecentSymbol(recent: List<String>, symbol: String, max: Int = 16): List<String> =
    (listOf(symbol) + recent.filter { it != symbol }).take(max)
