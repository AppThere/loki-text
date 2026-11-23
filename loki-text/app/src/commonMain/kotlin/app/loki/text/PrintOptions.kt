package app.loki.text

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Which pages a print covers; [wire] is the engine's `range`. */
internal enum class PrintRange(val label: String, val wire: String) {
    All("All pages", "all"), Current("Current page", "current"), Selection("Selection", "selection"), Pages("Pages", "custom"),
}

/** What the Print dialog collects: the arguments of `file.printPdf` besides the path. */
internal data class PrintOptions(
    val range: PrintRange = PrintRange.All,
    /** The page list for [PrintRange.Pages], as "1-3,5". */
    val pages: String = "",
    /** Pages to a sheet: "1", "2", "4", "6", "9", "16", "booklet" or "bookletReverse". */
    val perSheet: String = "1",
    /** "all", "odd" or "even". */
    val parity: String = "all",
    val copies: Int = 1,
    /** "none" keeps the document's own size; else a paper name (Letter, A4, Legal). */
    val paper: String = "none",
) {
    /** The `file.printPdf` call's parameters for a PDF written to [path]. */
    fun toParams(path: String): JsonObject = buildJsonObject {
        put("path", path)
        put("range", range.wire)
        if (range == PrintRange.Pages) put("pages", pages.trim())
        if (perSheet != "1") {
            val n = perSheet.toIntOrNull()
            if (n != null) put("pagesPerSheet", n) else put("pagesPerSheet", perSheet)
        }
        if (parity != "all") put("parity", parity)
        if (copies != 1) put("copies", copies.coerceIn(1, 999))
        if (paper != "none") put("paper", paper)
    }

    /**
     * The 1-based pages this prints out of [total], in order, or null when the page list is not valid. A selection's
     * pages are the engine's to say, so the current page stands in for them.
     */
    fun pagesPrinted(total: Int, current: Int): List<Int>? {
        val base = when (range) {
            PrintRange.All -> (1..total).toList()
            PrintRange.Current, PrintRange.Selection -> listOf(current.coerceIn(1, maxOf(total, 1)))
            PrintRange.Pages -> parsePageList(pages, total) ?: return null
        }
        return when (parity) {
            "odd" -> base.filter { it % 2 == 1 }
            "even" -> base.filter { it % 2 == 0 }
            else -> base
        }
    }

    /** How many sheets of paper the print uses, copies included, for [pageCount] pages. */
    fun sheets(pageCount: Int): Int {
        val perSide = when (perSheet) {
            "booklet", "bookletReverse" -> 2
            else -> perSheet.toIntOrNull()?.coerceAtLeast(1) ?: 1
        }
        // A booklet has two pages to a side and two sides to a sheet.
        val perSheetPages = if (perSheet.startsWith("booklet")) perSide * 2 else perSide
        return (pageCount + perSheetPages - 1) / perSheetPages * copies.coerceIn(1, 999)
    }
}

/** "1-3,5" as the pages 1, 2, 3, 5 within 1..[total] (sorted, once each); null for an empty list, a bad number, a backwards or out-of-range span. */
internal fun parsePageList(text: String, total: Int): List<Int>? {
    val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val out = sortedSetOf<Int>()
    for (p in parts) {
        val ends = p.split('-').map { it.trim() }
        if (ends.size > 2 || ends.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        val a = ends[0].toIntOrNull() ?: return null
        val b = (if (ends.size == 2) ends[1].toIntOrNull() else a) ?: return null
        if (a < 1 || b < a || b > total) return null
        for (n in a..b) out.add(n)
    }
    return out.toList()
}
