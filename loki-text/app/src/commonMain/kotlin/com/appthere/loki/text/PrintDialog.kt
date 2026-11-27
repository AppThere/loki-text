package com.appthere.loki.text

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val PER_SHEET = listOf("1" to "1", "2" to "2", "4" to "4", "6" to "6", "9" to "9", "16" to "16", "booklet" to "Booklet", "bookletReverse" to "Booklet (reversed)")
private val PAPERS = listOf("none" to "Document size", "Letter" to "Letter", "A4" to "A4", "Legal" to "Legal")

/**
 * File › Print: a picture of the pages that will print, the options, and what the print comes to. [pages] are the
 * pages already drawn for the window (the front of the document), so a preview of a later page is not always
 * available; the numbers always are. [onPrint] gets the options; the caller has the engine write the PDF and hands it over.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PrintDialog(
    pages: List<ImageBitmap>,
    totalPages: Int,
    currentPage: Int,
    hasSelection: Boolean,
    onPrint: (PrintOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var o by remember { mutableStateOf(PrintOptions()) }
    val printed = o.pagesPrinted(totalPages, currentPage)
    var shown by remember { mutableStateOf(0) }
    val previewPage = printed?.getOrNull(shown.coerceIn(0, maxOf((printed.size) - 1, 0)))
    val picture = previewPage?.let { pages.getOrNull(it - 1) }
    val problem = when {
        printed == null -> "The page list isn't valid: use numbers and spans within 1–$totalPages, like 1-3,5."
        printed.isEmpty() -> "No pages match: the odd/even choice leaves nothing to print."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Print") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The preview: the page that comes first in the print, with arrows through the rest.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    val count = printed?.size ?: 0
                    TextButton(onClick = { shown = (shown - 1).coerceAtLeast(0) }, enabled = shown > 0) { Text("‹") }
                    Box(
                        Modifier.height(220.dp).size(170.dp).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .semantics { contentDescription = if (previewPage != null) "Preview of page $previewPage" else "No preview" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (picture != null) Image(picture, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                        else Text(previewPage?.let { "Page $it" } ?: "", color = Color.Gray)
                    }
                    TextButton(onClick = { shown = (shown + 1).coerceAtMost(count - 1) }, enabled = shown < count - 1) { Text("›") }
                }
                Text(
                    previewPage?.let { "Page $it  ·  ${shown.coerceIn(0, (printed?.size ?: 1) - 1) + 1} of ${printed?.size}" } ?: "",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth(),
                )
                Text("Pages", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in PrintRange.entries) if (r != PrintRange.Selection || hasSelection) {
                        FilterChip(selected = o.range == r, onClick = { o = o.copy(range = r); shown = 0 }, label = { Text(r.label) })
                    }
                }
                if (o.range == PrintRange.Pages) {
                    OutlinedTextField(
                        value = o.pages, onValueChange = { o = o.copy(pages = it); shown = 0 }, singleLine = true,
                        label = { Text("Pages, like 1-3,5") }, isError = printed == null, modifier = Modifier.fillMaxWidth(),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((w, label) in listOf("all" to "All", "odd" to "Odd pages", "even" to "Even pages")) {
                        FilterChip(selected = o.parity == w, onClick = { o = o.copy(parity = w); shown = 0 }, label = { Text(label) })
                    }
                }
                Text("Pages to a sheet", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((w, label) in PER_SHEET) FilterChip(selected = o.perSheet == w, onClick = { o = o.copy(perSheet = w) }, label = { Text(label) })
                }
                Text("Paper", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((w, label) in PAPERS) FilterChip(selected = o.paper == w, onClick = { o = o.copy(paper = w) }, label = { Text(label) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Copies", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 8.dp))
                    TextButton(onClick = { o = o.copy(copies = (o.copies - 1).coerceAtLeast(1)) }, enabled = o.copies > 1) { Text("−") }
                    Text("${o.copies}", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { o = o.copy(copies = (o.copies + 1).coerceAtMost(999)) }) { Text("+") }
                }
                val summary = printed?.takeIf { it.isNotEmpty() }?.let { p ->
                    val sheets = o.sheets(p.size)
                    "${p.size} ${if (p.size == 1) "page" else "pages"} on $sheets ${if (sheets == 1) "sheet" else "sheets"}" + if (o.copies > 1) " (${o.copies} copies)" else ""
                }
                (problem ?: summary)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
            }
        },
        confirmButton = { TextButton(onClick = { onPrint(o) }, enabled = problem == null) { Text("Print") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
