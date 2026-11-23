package app.loki.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsTest {
    @Test fun settingsRoundTrip() {
        val s = Settings("dark", "FFF2CC").withRecent("/a/b.docx", 5L).withRecent("/c/d.odt", 9L)
        assertEquals(s, Settings.parse(s.toJson()))
    }

    @Test fun damagedOrMissingFileGivesDefaults() {
        assertEquals(Settings(), Settings.parse(null))
        assertEquals(Settings(), Settings.parse("{not json"))
        assertEquals(Settings(), Settings.parse("[1,2]"))
    }

    @Test fun badValuesAreDropped() {
        val s = Settings.parse("""{"theme":"purple","paperColor":"red","recents":[{"path":""},{"x":1},5,{"path":"/ok.docx","openedAt":"soon"}]}""")
        assertEquals("system", s.theme)
        assertNull(s.paperColor)
        assertEquals(listOf(RecentDocument("/ok.docx", 0L)), s.recents)
    }

    @Test fun reopeningMovesADocumentToTheFrontOnce() {
        val s = Settings().withRecent("/a", 1).withRecent("/b", 2).withRecent("/a", 3)
        assertEquals(listOf("/a", "/b"), s.recents.map { it.path })
        assertEquals(3L, s.recents.first().openedAt)
    }

    @Test fun theListIsCapped() {
        var s = Settings()
        for (i in 1..Settings.MAX_RECENTS + 5) s = s.withRecent("/f$i", i.toLong())
        assertEquals(Settings.MAX_RECENTS, s.recents.size)
        assertEquals("/f${Settings.MAX_RECENTS + 5}", s.recents.first().path)
    }

    @Test fun forgettingRemovesOnlyThatDocument() {
        val s = Settings().withRecent("/a", 1).withRecent("/b", 2).withoutRecent("/a")
        assertEquals(listOf("/b"), s.recents.map { it.path })
    }

    @Test fun recentDocumentsNameAndFolder() {
        val r = RecentDocument("/home/k/Documents/report.docx", 0)
        assertEquals("report.docx", r.name)
        assertEquals("/home/k/Documents", r.folder)
    }

    @Test fun ages() {
        val day = 86_400_000L
        assertEquals("", ageLabel(0, day))
        assertEquals("Today", ageLabel(10 * day, 10 * day + 1000))
        assertEquals("Yesterday", ageLabel(9 * day, 10 * day))
        assertEquals("3 days ago", ageLabel(7 * day, 10 * day))
        assertEquals("2 months ago", ageLabel(0 + 1, 61 * day + 1))
    }
}
