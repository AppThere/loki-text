package com.appthere.loki.text

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryTest {
    @Test fun readsTheEnginesState() {
        val s = parseRecoveryState(Json.parseToJsonElement("""{"enabled":true,"busy":false,"error":null,"documents":[
            {"id":"a1","name":"Report.docx","savedAt":"2026-11-23T10:44:12Z"},{"id":"b2","name":"","savedAt":""},{"name":"no id"},3]}"""))
        assertTrue(s.enabled)
        assertFalse(s.busy)
        assertEquals(listOf(RecoveryCopy("a1", "Report.docx", "2026-11-23T10:44:12Z"), RecoveryCopy("b2", "Untitled", "")), s.copies)
    }

    @Test fun anythingElseIsNoRecovery() {
        assertEquals(RecoveryState.NONE, parseRecoveryState(null))
        assertEquals(RecoveryState.NONE, parseRecoveryState(Json.parseToJsonElement("[1]")))
    }

    @Test fun timesAreShownToTheMinute() {
        assertEquals("2026-11-23 10:44", savedAtLabel("2026-11-23T10:44:12.5Z"))
        assertEquals("yesterday", savedAtLabel("yesterday"))
    }
}
