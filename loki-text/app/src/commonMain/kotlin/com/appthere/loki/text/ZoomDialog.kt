package com.appthere.loki.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put

internal val ZOOM_FITS = listOf("pageWidth" to "Page width", "onePage" to "Whole page", "multiplePages" to "Many pages")
private val ZOOM_PERCENTS = listOf(200, 150, 100, 75, 50)

/** The Zoom dialog's fields: a [fit] the window works out (`view.zoom`'s names), or blank for [percent] as typed. */
internal data class ZoomForm(val fit: String = "", val percent: String = "100")

/** From `view.state`. */
internal fun zoomFormFrom(view: JsonObject): ZoomForm {
    val zoom = (view["zoom"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() && it > 0f } ?: 1f
    val fit = (view["fit"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return ZoomForm(fit.takeIf { f -> ZOOM_FITS.any { it.first == f } }.orEmpty(), (zoom * 100).roundToInt().toString())
}

/** The `view.zoom` params for [f]; null while the percentage isn't a whole number in [range], what this app draws. */
internal fun zoomParams(f: ZoomForm, range: IntRange): JsonObject? {
    if (f.fit.isNotEmpty()) return buildJsonObject { put("value", f.fit) }
    val percent = f.percent.trim().removeSuffix("%").trim().toIntOrNull()?.takeIf { it in range } ?: return null
    return buildJsonObject { put("value", percent) }
}

/** View › Zoom: a percentage, or a fit to the window. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ZoomDialog(start: ZoomForm, range: IntRange, onApply: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(start) }
    val params = zoomParams(f, range)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Zoom")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Zoom to"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in ZOOM_PERCENTS) if (p in range) {
                        FilterChip(f.fit.isEmpty() && f.percent == p.toString(), { f = ZoomForm(percent = p.toString()) }, label = { Text("$p%") })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, label) in ZOOM_FITS) FilterChip(f.fit == id, { f = f.copy(fit = id) }, label = { Text(tr(label)) })
                }
                OutlinedTextField(
                    f.percent, { f = ZoomForm(percent = it) },
                    label = { Text(tr("Percent") + " (${range.first}–${range.last})") }, singleLine = true,
                    isError = params == null, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = params != null, onClick = { params?.let(onApply) }) { Text(tr("OK")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
