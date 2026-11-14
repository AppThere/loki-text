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

/** Position: In Line with Text, then the nine places on the page, as `arrange.position` presets. */
internal val POSITIONS: List<Pair<String, String?>> = listOf("In Line with Text" to null) + listOf(
    "Top Left" to "topLeft", "Top Center" to "topCenter", "Top Right" to "topRight",
    "Middle Left" to "middleLeft", "Middle Center" to "middleCenter", "Middle Right" to "middleRight",
    "Bottom Left" to "bottomLeft", "Bottom Center" to "bottomCenter", "Bottom Right" to "bottomRight",
)

/** Wrap Text choices as `arrange.wrap` names them. */
internal val WRAPS = listOf(
    "In Line with Text" to "inline", "Square" to "square", "Tight" to "tight", "Through" to "through",
    "Top and Bottom" to "topAndBottom", "Behind Text" to "behindText", "In Front of Text" to "inFrontOfText",
)

/** The Wrap Text menu's rows after the wraps: the anchoring of a floating object. */
private val ANCHORING = listOf("Move with Text", "Fix Position on Page")

/** The Wrap Text menu: its labels, and the wrap marked current (the menu marks one row, so not the anchoring). */
internal fun wrapMenu(state: ArrangeState?): Pair<List<String>, String?> =
    (WRAPS.map { it.first } + ANCHORING) to WRAPS.firstOrNull { it.second == state?.wrap }?.first

/** The command a Wrap Text menu row runs. */
internal fun wrapChoice(i: Int): Pair<String, JsonObject>? = when {
    i < WRAPS.size -> WRAPS.getOrNull(i)?.let { "arrange.wrap" to buildJsonObject { put("wrap", it.second) } }
    i == WRAPS.size -> "arrange.moveWithText" to buildJsonObject { put("value", true) }
    i == WRAPS.size + 1 -> "arrange.moveWithText" to buildJsonObject { put("value", false) }
    else -> null
}

/** The command a Position menu row runs: In Line with Text is a wrap; the rest are presets. */
internal fun positionChoice(i: Int): Pair<String, JsonObject>? {
    val (_, preset) = POSITIONS.getOrNull(i) ?: return null
    return if (preset == null) "arrange.wrap" to buildJsonObject { put("wrap", "inline") }
    else "arrange.position" to buildJsonObject { put("preset", preset) }
}

private val ROTATIONS = listOf("Rotate Right 90°" to "right", "Rotate Left 90°" to "left", "Flip Vertical" to "flipVertical", "Flip Horizontal" to "flipHorizontal")
private val ALIGNS = listOf("Align Left" to "left", "Align Center" to "center", "Align Right" to "right")

/** Commands the Arrange group places itself; any other in its [items] goes in More. */
private val ARRANGE_PLACED = setOf("arrange.bringForward", "arrange.sendBackward", "arrange.selectionPane", "arrange.group", "arrange.ungroup")

/**
 * Arrange (Layout, and Picture Format): Position and Wrap Text menus, Bring Forward and Send
 * Backward, and menus for Align and Rotate, with Group, Ungroup and the Selection Pane.
 */
@Composable
fun ArrangeGroup(env: RibbonEnv, items: List<RibbonItem>) {
    val state = env.state
    val rest = items.filter { it.id !in ARRANGE_PLACED }
    fun run(choice: Pair<String, JsonObject>?) { choice?.let { (id, p) -> env.onRunWith(id, p) } }
    @Composable fun plain(id: String, fallback: String) =
        RibbonButton(id, env.label(id, fallback), ButtonSize.Labeled, env.shortcut(id), env.reason(id), id in state.on) { env.onRun(id) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val inline = state.arrange?.wrap == "inline"
        IconMenuButton("arrange.position", env.label("arrange.position", "Position"), POSITIONS.map { it.first }, if (inline) POSITIONS.first().first else null, env.reason("arrange.position"), ButtonSize.Big) { i ->
            run(positionChoice(i))
        }
        val (wraps, current) = wrapMenu(state.arrange)
        IconMenuButton("arrange.wrap", env.label("arrange.wrap", "Wrap Text"), wraps, current, env.reason("arrange.wrap"), ButtonSize.Big) { i -> run(wrapChoice(i)) }
        Column {
            plain("arrange.bringForward", "Bring Forward")
            plain("arrange.sendBackward", "Send Backward")
            plain("arrange.selectionPane", "Selection Pane")
        }
        Column {
            IconMenuButton("arrange.align", env.label("arrange.align", "Align"), ALIGNS.map { it.first }, null, env.reason("arrange.align"), ButtonSize.Labeled) { i ->
                ALIGNS.getOrNull(i)?.let { env.onRunWith("arrange.align", buildJsonObject { put("value", it.second) }) }
            }
            if (env.items.containsKey("arrange.group")) plain("arrange.group", "Group")
            IconMenuButton("arrange.rotate", env.label("arrange.rotate", "Rotate"), ROTATIONS.map { it.first }, null, env.reason("arrange.rotate"), ButtonSize.Labeled) { i ->
                ROTATIONS.getOrNull(i)?.let { env.onRunWith("arrange.rotate", buildJsonObject { put("direction", it.second) }) }
            }
        }
        if (rest.isNotEmpty() || env.items.containsKey("arrange.ungroup")) Column {
            if (env.items.containsKey("arrange.ungroup")) plain("arrange.ungroup", "Ungroup")
            if (rest.isNotEmpty()) CommandMenu(rest, state, env.onRun) { open -> MoreLabeledButton(open) }
        }
    }
}
