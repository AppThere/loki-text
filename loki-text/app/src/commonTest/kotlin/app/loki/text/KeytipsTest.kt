package app.loki.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeytipsTest {
    @Test fun controlsTakeALetterOfTheirOwnName() {
        val codes = assignKeytips(listOf("Paste", "Cut", "Copy", "Bold", "Italic", "Underline"))
        assertEquals(listOf("P", "C", "O", "B", "I", "U"), codes)
    }

    @Test fun keytipsAreUniqueAndNoneStartsAnother() {
        val labels = List(60) { "Control ${it % 7}" } + listOf("Bold", "Book", "Bullets")
        val codes = assignKeytips(labels)
        val given = codes.filter { it.isNotEmpty() }
        assertEquals(given.size, given.toSet().size, "unique")
        for (a in given) for (b in given) if (a != b) assertTrue(!b.startsWith(a), "$a starts $b")
        // More controls than letters: the rest get two-letter keytips, then none.
        assertTrue(given.any { it.length == 2 })
    }

    @Test fun yAndZOnlyBeginTwoLetterKeytips() {
        assertTrue(assignKeytips(listOf("Zoom", "Yes")).none { it == "Z" || it == "Y" })
    }

    @Test fun tabsHaveTheDesktopsLettersAndToolTabsJAndADigit() {
        val t = tabKeytips(listOf("Home", "Insert", "Table Layout", "Picture Format", "SmartArt Design"))
        assertEquals("H", t["Home"]); assertEquals("N", t["Insert"]); assertEquals("JL", t["Table Layout"])
        assertEquals(setOf("J1", "J2"), setOf(t["Picture Format"], t["SmartArt Design"]))
    }

    @Test fun toolbarNumbersAreReachedBesideTheTabLettersAndControlsWithTheirTab() {
        val k = KeytipState()
        var ran = ""
        k.controls += KeytipControl("Save", fixed = "1") { ran = "save" }
        k.controls += KeytipControl("Bold") { ran = "bold" }
        assertEquals(null, k.codeOf(k.controls[0]))
        k.tabs = listOf("Home")
        k.openTab = {}
        k.showTabs()
        assertEquals("1", k.codeOf(k.controls[0]))
        assertEquals(null, k.codeOf(k.controls[1]))
        k.type('1'); assertEquals("save", ran); assertEquals(KeytipPhase.Off, k.phase)
        k.showTabs(); k.type('H'); assertEquals(KeytipPhase.Controls, k.phase)
        assertEquals("B", k.codeOf(k.controls[1]))
        k.type('B'); assertEquals("bold", ran)
    }
}
