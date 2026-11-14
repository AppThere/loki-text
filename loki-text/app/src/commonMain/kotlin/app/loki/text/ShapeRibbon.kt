package app.loki.text

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

/** The Shape Format groups that [ShapeGroup] draws itself; the rest keep their generic buttons. */
val SHAPE_GROUPS = setOf("Insert Shapes", "Shape Styles", "WordArt Styles", "Text", "Accessibility", "Arrange")

private fun obj(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(build)

/** Outline weights in points. */
internal val OUTLINE_WEIGHTS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2.25f, 3f, 4.5f, 6f)

/** Shadow presets: label, and the engine's name. */
private val SHADOWS = listOf(
    "Offset: Bottom Right" to "offsetBottomRight", "Offset: Bottom" to "offsetBottom", "Offset: Bottom Left" to "offsetBottomLeft",
    "Offset: Right" to "offsetRight", "Offset: Center" to "offsetCenter", "Offset: Left" to "offsetLeft",
    "Offset: Top Right" to "offsetTopRight", "Offset: Top" to "offsetTop", "Offset: Top Left" to "offsetTopLeft",
)

/** Shape Effects: one menu of shadows, glows and soft edges. A key that is left out keeps its setting. */
internal val SHAPE_EFFECTS: List<Pair<String, JsonObject>> = buildList {
    add("No Shadow" to obj { put("shadow", JsonNull) })
    for ((l, p) in SHADOWS) add("Shadow – $l" to obj { put("shadow", p) })
    add("No Glow" to obj { put("glow", JsonNull) })
    for (pt in listOf(5, 10, 18)) add("Glow $pt pt" to obj { put("glow", pt) })
    add("No Soft Edges" to obj { put("softEdge", JsonNull) })
    for (pt in listOf(2.5f, 5f, 10f)) add("Soft Edges $pt pt" to obj { put("softEdge", pt) })
}

/** WordArt styles: label, and the engine's name. */
internal val WORDART_STYLES = listOf(
    "Solid" to "solid", "Outlined" to "outlined", "Hollow" to "hollow", "Gradient" to "gradient",
    "Shadowed" to "shadowed", "Glowing" to "glowing", "Reflected" to "reflected", "Sunset" to "sunset",
)

/** Text warps: label, and the DrawingML preset the engine takes. */
internal val WORDART_TRANSFORMS = listOf(
    "None" to "none", "Arch Up" to "textArchUp", "Arch Down" to "textArchDown", "Circle" to "textCircle", "Wave" to "textWave1",
    "Slant Up" to "textSlantUp", "Slant Down" to "textSlantDown", "Inflate" to "textInflate", "Deflate" to "textDeflate",
    "Chevron" to "textChevron", "Triangle" to "textTriangle",
)

internal val TEXT_DIRECTIONS = listOf("Horizontal" to "horz", "Rotate all text 90°" to "vert", "Rotate all text 270°" to "vert270", "Stacked" to "wordArtVert")
internal val TEXT_ALIGNMENTS = listOf("Top" to "t", "Middle" to "ctr", "Bottom" to "b")

/** Shape Fill: a colour, or null for no fill. */
internal fun fillParams(color: String?): JsonObject = obj { if (color == null) put("color", JsonNull) else put("color", color) }

/** Shape Outline: the engine replaces the colour whenever it is sent, so the weight always travels with one. */
internal fun outlineParams(color: String?, weightPt: Float): JsonObject = obj {
    if (color == null) put("color", JsonNull) else put("color", color)
    put("width", weightPt.coerceIn(0f, 72f))
}

/** The label for a weight, without a trailing `.0`. */
internal fun weightText(pt: Float): String = if (pt == pt.toInt().toFloat()) "${pt.toInt()} pt" else "$pt pt"

/** The Shape Format group called [name]. */
@Composable
fun ShapeGroup(name: String, env: RibbonEnv) {
    var dialog by remember { mutableStateOf<InsertDialog?>(null) }
    dialog?.let { d -> InsertDialogView(d, { p -> dialog = null; env.onRunWith(d.command, p) }, { dialog = null }) }
    // The outline's colour and weight are sent together, so the last choice of each is kept.
    var outlineColor by remember { mutableStateOf<String?>("000000") }
    var outlineWeight by remember { mutableStateOf(1f) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        when (name) {
            "Insert Shapes" -> IconMenuButton("shape.change", env.label("shape.change", "Change Shape"), SHAPES.map { it.first }, null, env.reason("shape.change"), ButtonSize.Big) { i ->
                val kind = SHAPES.getOrNull(i)?.second ?: return@IconMenuButton
                env.onRunWith("shape.change", obj { put("kind", kind) })
            }
            "Shape Styles" -> {
                Column {
                    ColorSplitButton("shape.fill", env.label("shape.fill", "Shape Fill"), FONT_COLORS, FONT_COLORS[4], "No Fill", env.reason("shape.fill"), custom = true) { c ->
                        env.onRunWith("shape.fill", fillParams(c))
                    }
                    ColorSplitButton("shape.outline", env.label("shape.outline", "Shape Outline"), FONT_COLORS, FONT_COLORS[9], "No Outline", env.reason("shape.outline"), custom = true) { c ->
                        outlineColor = c
                        env.onRunWith("shape.outline", outlineParams(c, outlineWeight))
                    }
                    IconMenuButton("app.shapeWeight", "Weight", OUTLINE_WEIGHTS.map(::weightText), weightText(outlineWeight), env.reason("shape.outline"), ButtonSize.Labeled) { i ->
                        val w = OUTLINE_WEIGHTS.getOrNull(i) ?: return@IconMenuButton
                        outlineWeight = w
                        env.onRunWith("shape.outline", outlineParams(outlineColor, w))
                    }
                }
                IconMenuButton("shape.effects", env.label("shape.effects", "Shape Effects"), SHAPE_EFFECTS.map { it.first }, null, env.reason("shape.effects"), ButtonSize.Big) { i ->
                    val p = SHAPE_EFFECTS.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("shape.effects", p)
                }
            }
            "WordArt Styles" -> {
                IconMenuButton("wordArt.style", env.label("wordArt.style", "WordArt Styles"), WORDART_STYLES.map { it.first }, null, env.reason("wordArt.style"), ButtonSize.Big) { i ->
                    val s = WORDART_STYLES.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("wordArt.style", obj { put("style", s) })
                }
                Column {
                    ColorSplitButton("wordArt.textFill", env.label("wordArt.textFill", "Text Fill"), FONT_COLORS, FONT_COLORS[9], "No Fill", env.reason("wordArt.textFill"), custom = true) { c ->
                        env.onRunWith("wordArt.textFill", obj { if (c == null) put("fill", "none") else put("fill", c) })
                    }
                    ColorSplitButton("wordArt.textOutline", env.label("wordArt.textOutline", "Text Outline"), FONT_COLORS, FONT_COLORS[9], "No Outline", env.reason("wordArt.textOutline"), custom = true) { c ->
                        env.onRunWith("wordArt.textOutline", if (c == null) obj { put("outline", "none") } else obj { put("color", c) })
                    }
                    IconMenuButton("wordArt.transform", env.label("wordArt.transform", "Transform"), WORDART_TRANSFORMS.map { it.first }, null, env.reason("wordArt.transform"), ButtonSize.Labeled) { i ->
                        val t = WORDART_TRANSFORMS.getOrNull(i)?.second ?: return@IconMenuButton
                        env.onRunWith("wordArt.transform", obj { put("preset", t) })
                    }
                }
            }
            "Text" -> Column {
                IconMenuButton("shape.textDirection", env.label("shape.textDirection", "Text Direction"), TEXT_DIRECTIONS.map { it.first }, null, env.reason("shape.textDirection"), ButtonSize.Labeled) { i ->
                    val d = TEXT_DIRECTIONS.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("shape.textDirection", obj { put("dir", d) })
                }
                IconMenuButton("shape.alignText", env.label("shape.alignText", "Align Text"), TEXT_ALIGNMENTS.map { it.first }, null, env.reason("shape.alignText"), ButtonSize.Labeled) { i ->
                    val a = TEXT_ALIGNMENTS.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("shape.alignText", obj { put("anchor", a) })
                }
            }
            "Accessibility" -> RibbonButton("object.altText", env.label("object.altText", "Alt Text"), ButtonSize.Big, disabledReason = env.reason("object.altText")) {
                dialog = InsertDialog.AltText
            }
            "Arrange" -> ArrangeGroup(env, emptyList())
        }
    }
}
