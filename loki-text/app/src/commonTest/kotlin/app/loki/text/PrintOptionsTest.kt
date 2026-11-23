package app.loki.text

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrintOptionsTest {
    @Test fun pageListsAreSortedWithinTheDocument() {
        assertEquals(listOf(1, 2, 3, 5), parsePageList("5, 1-3", 10))
        assertEquals(listOf(4), parsePageList("4-4", 4))
        for (bad in listOf("", " ", "0", "3-1", "1-11", "a", "1--2", "1,,x", "-3", "1-2-3")) assertNull(parsePageList(bad, 10), bad)
    }

    @Test fun theRangePicksThePages() {
        assertEquals(listOf(1, 2, 3, 4), PrintOptions().pagesPrinted(4, 2))
        assertEquals(listOf(2), PrintOptions(range = PrintRange.Current).pagesPrinted(4, 2))
        assertEquals(listOf(2, 4), PrintOptions(parity = "even").pagesPrinted(4, 1))
        assertEquals(listOf(3), PrintOptions(range = PrintRange.Pages, pages = "2-3", parity = "odd").pagesPrinted(4, 1))
        assertNull(PrintOptions(range = PrintRange.Pages, pages = "9").pagesPrinted(4, 1))
    }

    @Test fun moreCopiesAndFewerPagesPerSheetUseMoreSheets() {
        val one = PrintOptions().sheets(8)
        assertEquals(8, one)
        assert(PrintOptions(perSheet = "4").sheets(8) < one)
        assert(PrintOptions(copies = 3).sheets(8) > one)
        assert(PrintOptions(perSheet = "booklet").sheets(8) < one)
    }

    @Test fun paramsCarryOnlyWhatWasChosen() {
        assertEquals("""{"path":"/p.pdf","range":"all"}""", PrintOptions().toParams("/p.pdf").toString())
        val p = PrintOptions(PrintRange.Pages, " 1-3,5 ", "booklet", "odd", 2, "A4").toParams("/p.pdf")
        assertEquals(Json.parseToJsonElement("""{"path":"/p.pdf","range":"custom","pages":"1-3,5","pagesPerSheet":"booklet","parity":"odd","copies":2,"paper":"A4"}"""), p)
        assertEquals(Json.parseToJsonElement("""{"path":"/p.pdf","range":"all","pagesPerSheet":4}"""), PrintOptions(perSheet = "4").toParams("/p.pdf"))
    }
}
