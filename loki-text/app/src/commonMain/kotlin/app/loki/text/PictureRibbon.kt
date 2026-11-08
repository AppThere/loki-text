package app.loki.text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** The Picture Format groups that [PictureGroup] draws itself. */
val PICTURE_GROUPS = setOf("Adjust", "Picture Styles", "Accessibility", "Arrange", "Size")

private fun params(vararg kv: Pair<String, Any>): JsonObject = buildJsonObject {
    for ((k, v) in kv) when (v) {
        is String -> put(k, v)
        is Number -> put(k, v)
        is Boolean -> put(k, v)
    }
}

/** Corrections: each sets brightness, contrast and sharpness together, from the original picture. */
internal val CORRECTIONS: List<Pair<String, JsonObject>> = buildList {
    add("Normal" to params("brightness" to 0, "contrast" to 0, "sharpen" to 0))
    for (b in listOf(-40, -20, 20, 40)) add("Brightness ${signed(b)}%" to params("brightness" to b))
    for (c in listOf(-40, -20, 20, 40)) add("Contrast ${signed(c)}%" to params("contrast" to c))
    add("Sharpen 40%" to params("sharpen" to 40))
    add("Soften 40%" to params("sharpen" to -40))
}

/** A whole number with its sign: `+20`, `−20`, `0`. */
internal fun signed(n: Int): String = when {
    n > 0 -> "+$n"
    n < 0 -> "−${-n}"
    else -> "0"
}

/** Color: saturation levels, then recolours. */
internal val COLORS: List<Pair<String, JsonObject>> =
    listOf(0, 33, 66, 100, 200, 300, 400).map { v -> (if (v == 100) "Saturation 100% (Normal)" else "Saturation $v%") to params("mode" to "saturation", "saturation" to v) } +
        listOf("Grayscale" to "grayscale", "Sepia" to "sepia", "Washout" to "washout", "Black and White" to "blackAndWhite").map { (l, m) -> l to params("mode" to m) }

internal val EFFECTS = listOf("None" to "none", "Blur" to "blur", "Sharpen" to "sharpen", "Invert" to "invert", "Posterize" to "posterize", "Pixelate" to "pixelate")

internal val TRANSPARENCIES = listOf(0, 15, 30, 50, 65, 80, 95)

internal val PICTURE_STYLES = listOf("Simple Frame" to "simpleFrame", "Thick Frame" to "thickFrame", "Rounded" to "rounded", "Soft Edge" to "softEdge", "Shadow" to "shadow")

/** Border widths in pixels of the picture, as the border panel offers them. */
private val PICTURE_BORDER_WIDTHS = listOf(1, 2, 4, 8, 16)

/** One side of the picture's display size; the other follows, keeping its proportions. */
internal fun pictureSizeParams(key: String, pt: Float): JsonObject = buildJsonObject { put(key, pt); put("lockAspect", true) }

/** The most of one axis a crop may take: the two sides keep at least 5% of the picture between them. */
private const val MAX_CROP = 0.95f

/**
 * Crop: four boxes in percent (left, top, right, bottom) as fractions, or null when one isn't a
 * number from 0 to 95 or an axis's two sides together leave less than 5% of the picture.
 */
internal fun cropParams(values: List<String>): JsonObject? {
    if (values.size != 4) return null
    val f = values.map { v -> v.trim().removeSuffix("%").trim().toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..95f }?.div(100f) ?: return null }
    if (f[0] + f[2] > MAX_CROP || f[1] + f[3] > MAX_CROP) return null
    return buildJsonObject { put("left", f[0]); put("top", f[1]); put("right", f[2]); put("bottom", f[3]) }
}

/** A crop fraction as the dialog shows it, in whole percent. */
internal fun cropText(f: Float): String = (f * 100f).roundToInt().coerceIn(0, 95).toString()

/** Alt text for a picture: the description is always sent (the engine needs it), blank or not. */
internal fun pictureAltParams(text: String, title: String, decorative: Boolean): JsonObject =
    buildJsonObject { put("text", text.trim()); put("title", title.trim()); put("decorative", decorative) }

/** The Picture Format group called [name]. */
@Composable
fun PictureGroup(name: String, env: RibbonEnv) {
    val pic = env.state.picture
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        when (name) {
            "Adjust" -> {
                RibbonButton("picture.removeBackground", env.label("picture.removeBackground", "Remove Background"), ButtonSize.Big, disabledReason = env.reason("picture.removeBackground")) {
                    env.onRunWith("picture.removeBackground", JsonObject(emptyMap()))
                }
                Column {
                    PresetMenu(env, "picture.corrections", "Corrections", CORRECTIONS)
                    PresetMenu(env, "picture.color", "Color", COLORS)
                    PresetMenu(env, "picture.effects", "Artistic Effects", EFFECTS.map { (l, e) -> l to params("effect" to e) })
                }
                Column {
                    PresetMenu(env, "picture.transparency", "Transparency", TRANSPARENCIES.map { "$it%" to params("percent" to it) })
                    RibbonButton("picture.compress", env.label("picture.compress", "Compress Pictures"), ButtonSize.Labeled, disabledReason = env.reason("picture.compress")) {
                        env.onRunWith("picture.compress", JsonObject(emptyMap()))
                    }
                    ChangePictureButton(env)
                }
                Column {
                    RibbonButton("picture.reset", env.label("picture.reset", "Reset Picture"), ButtonSize.Labeled, disabledReason = env.reason("picture.reset")) {
                        env.onRunWith("picture.reset", JsonObject(emptyMap()))
                    }
                    // Recolours one-colour line art (an inserted icon) without redrawing it.
                    ColorSplitButton("picture.iconColor", env.label("picture.iconColor", "Icon Color"), FONT_COLORS, FONT_COLORS[9], "Black", env.reason("picture.iconColor"), custom = true) { c ->
                        env.onRunWith("picture.iconColor", buildJsonObject { put("color", c ?: "000000") })
                    }
                }
            }
            "Picture Styles" -> {
                IconMenuButton("picture.style", env.label("picture.style", "Picture Styles"), PICTURE_STYLES.map { it.first }, null, env.reason("picture.style"), ButtonSize.Big) { i ->
                    val style = PICTURE_STYLES.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("picture.style", buildJsonObject { put("style", style) })
                }
                PictureBorderButton(env)
            }
            "Arrange" -> ArrangeGroup(env, emptyList())
            "Accessibility" -> {
                var open by remember { mutableStateOf(false) }
                if (open) PictureAltTextDialog(pic, { p -> open = false; env.onRunWith("picture.altText", p) }, { open = false })
                RibbonButton("picture.altText", env.label("picture.altText", "Alt Text"), ButtonSize.Big, disabledReason = env.reason("picture.altText")) { open = true }
            }
            "Size" -> {
                var cropping by remember { mutableStateOf(false) }
                if (cropping) CropDialog(pic?.crop ?: listOf(0f, 0f, 0f, 0f), { p -> cropping = false; env.onRunWith("picture.crop", p) }, { cropping = false })
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    InchField("app.pictureHeight", "Height", pic?.height, env.reason("picture.size")) { pt -> env.onRunWith("picture.size", pictureSizeParams("height", pt)) }
                    InchField("app.pictureWidth", "Width", pic?.width, env.reason("picture.size")) { pt -> env.onRunWith("picture.size", pictureSizeParams("width", pt)) }
                }
                Column {
                    RibbonButton("picture.crop", env.label("picture.crop", "Crop"), ButtonSize.Labeled, disabledReason = env.reason("picture.crop")) { cropping = true }
                    RibbonButton("app.pictureOriginal", "Original Size", ButtonSize.Labeled, disabledReason = env.reason("picture.size")) {
                        env.onRunWith("picture.size", buildJsonObject { put("original", true) })
                    }
                }
            }
        }
    }
}

/** A labelled button opening [choices]; each runs [id] with its parameters. */
@Composable
private fun PresetMenu(env: RibbonEnv, id: String, fallback: String, choices: List<Pair<String, JsonObject>>) {
    IconMenuButton(id, env.label(id, fallback), choices.map { it.first }, null, env.reason(id), ButtonSize.Labeled) { i ->
        val p = choices.getOrNull(i)?.second ?: return@IconMenuButton
        env.onRunWith(id, p)
    }
}

/** Change Picture: asks for an image file and swaps it in, keeping the picture's size and place. */
@Composable
private fun ChangePictureButton(env: RibbonEnv) {
    val scope = rememberCoroutineScope()
    RibbonButton("picture.change", env.label("picture.change", "Change Picture"), ButtonSize.Labeled, disabledReason = env.reason("picture.change")) {
        scope.launch {
            val path = withContext(Dispatchers.Default) { pickFile(save = false) } ?: return@launch
            env.onRunWith("picture.change", buildJsonObject { put("path", path) })
        }
    }
}

/** Picture Border: choose a width and colour, then Apply (the border replaces any earlier one). */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PictureBorderButton(env: RibbonEnv) {
    var open by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(4) }
    var color by remember { mutableStateOf(FONT_COLORS.first()) }
    Box {
        RibbonButton("picture.border", env.label("picture.border", "Picture Border"), ButtonSize.Big, disabledReason = env.reason("picture.border")) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val head = MaterialTheme.typography.labelSmall
            val dim = MaterialTheme.colorScheme.outline
            Text("Width (pixels)", style = head, color = dim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            FlowRow(Modifier.padding(horizontal = 8.dp), maxItemsInEachRow = 5) {
                for (w in PICTURE_BORDER_WIDTHS) {
                    FilterChip(selected = width == w, onClick = { width = w }, label = { Text("$w", style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.padding(horizontal = 2.dp))
                }
            }
            Text("Color", style = head, color = dim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            FlowRow(Modifier.padding(horizontal = 8.dp), maxItemsInEachRow = 6) {
                for (sw in FONT_COLORS) {
                    Box(
                        Modifier.padding(2.dp).size(26.dp)
                            .background(sw.color, RoundedCornerShape(4.dp))
                            .border(if (color.param == sw.param) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                            .clickable { color = sw },
                    )
                }
            }
            Text(
                "Apply Border",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clickable {
                    open = false
                    env.onRunWith("picture.border", buildJsonObject { put("width", width); put("color", color.param) })
                }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Text("Reset Picture removes a border.", style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun PictureAltTextDialog(pic: PictureState?, onSubmit: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(pic?.alt.orEmpty()) }
    var title by remember { mutableStateOf(pic?.title.orEmpty()) }
    var decorative by remember { mutableStateOf(pic?.decorative == true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alt Text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("Description") }, minLines = 3, enabled = !decorative, modifier = Modifier.width(288.dp))
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, enabled = !decorative, modifier = Modifier.width(288.dp))
                Row(Modifier.clickable { decorative = !decorative }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(decorative, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                    Text("Mark as decorative", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton({ onSubmit(pictureAltParams(text, title, decorative)) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CropDialog(crop: List<Float>, onSubmit: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var values by remember { mutableStateOf(crop.map(::cropText)) }
    fun set(i: Int, v: String) { values = values.toMutableList().also { it[i] = v.filter { c -> c.isDigit() || c == '.' }.take(4) } }
    val params = cropParams(values)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Percent of the picture to trim from each side.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(values[0], { set(0, it) }, label = { Text("Left %") }, singleLine = true, modifier = Modifier.width(120.dp))
                    OutlinedTextField(values[2], { set(2, it) }, label = { Text("Right %") }, singleLine = true, modifier = Modifier.width(120.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(values[1], { set(1, it) }, label = { Text("Top %") }, singleLine = true, modifier = Modifier.width(120.dp))
                    OutlinedTextField(values[3], { set(3, it) }, label = { Text("Bottom %") }, singleLine = true, modifier = Modifier.width(120.dp))
                }
                TextButton({ values = listOf("0", "0", "0", "0") }) { Text("Remove Crop") }
            }
        },
        confirmButton = { TextButton({ params?.let(onSubmit) }, enabled = params != null) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
