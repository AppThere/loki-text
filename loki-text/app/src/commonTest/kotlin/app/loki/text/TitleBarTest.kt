package app.loki.text

import kotlin.test.Test
import kotlin.test.assertEquals

class TitleBarTest {
    @Test
    fun titleShowsTheFileNameAndUnsavedMark() {
        assertEquals("Untitled — $APP_NAME", windowTitle(null, false))
        assertEquals("● Untitled — $APP_NAME", windowTitle("", true))
        assertEquals("report.docx — $APP_NAME", windowTitle("/home/kevin/docs/report.docx", false))
        assertEquals("● notes.docx — $APP_NAME", windowTitle("C:\\Users\\k\\notes.docx", true))
    }
}
