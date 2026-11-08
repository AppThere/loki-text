package app.loki.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileMenuTest {
    @Test
    fun entriesAreUniqueAndCoverTheFileActions() {
        val ids = FILE_MENU.filterNotNull().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in listOf("file.new", "file.open", "file.save", "file.saveAs", "file.print")) assertTrue(id in ids, id)
    }

    @Test
    fun noDividerLeadsTrailsOrDoubles() {
        assertTrue(FILE_MENU.first() != null && FILE_MENU.last() != null)
        assertTrue(FILE_MENU.zipWithNext().none { (a, b) -> a == null && b == null })
    }
}
