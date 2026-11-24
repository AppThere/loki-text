package app.loki.text

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ShapePanesTest {
    private val outline = listOf(SmartArtItem("Plan"), SmartArtItem("Scope", 1), SmartArtItem("Budget", 2), SmartArtItem("Build"))

    @Test fun anItemGoesInNoFurtherThanOneBelowTheOneBeforeIt() {
        assertEquals(1, demoteItem(outline, 3)[3].level)
        assertEquals(2, demoteItem(outline, 2)[2].level + 0, "already one below its parent: no further")
        assertEquals(outline, demoteItem(outline, 0), "the first item stays at the top")
    }

    @Test fun anItemComesOutWithTheItemsUnderIt() {
        val out = promoteItem(outline, 1)
        assertEquals(listOf(0, 0, 1, 0), out.map { it.level })
        assertEquals(outline, promoteItem(outline, 0))
    }

    @Test fun addingAndRemovingKeepTheOutlineWellFormed() {
        val added = addItemAfter(outline, 1)
        assertEquals(SmartArtItem("", 1), added[2])
        // Taking out "Scope" would leave "Budget" two below "Plan": it comes out to one.
        assertEquals(listOf(0, 1, 0), removeItem(outline, 1).map { it.level })
        assertEquals(listOf(SmartArtItem("Only")), removeItem(listOf(SmartArtItem("Only")), 0), "the last item stays")
    }

    @Test fun theOutlineIsReadAndSentWhole() {
        val read = parseSmartArt(Json.parseToJsonElement("""{"layout":"process","items":[{"level":0,"text":"Plan"},{"level":9,"text":"Deep","color":"FF0000"}]}"""))
        assertEquals(listOf(SmartArtItem("Plan"), SmartArtItem("Deep", 7, "FF0000")), read)
        assertEquals("""{"items":[{"text":"Plan"},{"text":"Deep","level":7,"color":"FF0000"}]}""", smartArtItemsParams(read!!).toString())
    }

    @Test fun inchesAreShownToTwoPlaces() {
        assertEquals("1", formatInches(72f))
        assertEquals("1.5", formatInches(108f))
        assertEquals("0.33", formatInches(24f))
    }
}
