package com.appthere.loki.text

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptionsDialogTest {
    private val reported = Json.parseToJsonElement(
        """{"author":"Ann","initials":"A","units":"inches","unitsFromRegion":true,"region":{"language":"pl","territory":"PL"},"regionFromSystem":false,
        "typingReplacesSelection":true,"insertKeyOvertype":false,"overtype":false,"marks":{"tabs":true,"spaces":false,"paragraphs":false,"hidden":false},
        "checkSpelling":true,"markGrammar":false,"ignoreUppercase":true,"ignoreNumbers":false,"ignoreInternet":false,
        "dictionariesFolder":null,"customDictionary":null,"userTemplatesFolder":"/home/a/T"}""",
    )
    private val form = parseOptions(reported)!!

    @Test fun readsWhatTheEngineReports() {
        assertEquals("Ann", form.author)
        assertEquals("auto", form.units)
        assertEquals("pl-PL", form.region)
        assertTrue(form.markTabs && !form.markSpaces && !form.markGrammar)
        assertEquals("", form.dictionariesFolder)
        assertEquals("/home/a/T", form.userTemplatesFolder)
    }

    @Test fun regionFollowingTheSystemReadsEmpty() =
        assertEquals("", parseOptions(Json.parseToJsonElement("""{"region":{"language":"en","territory":"US"},"regionFromSystem":true}"""))!!.region)

    @Test fun unchangedSendsNothing() = assertTrue(form.changesFrom(form).isEmpty())

    @Test fun onlyChangedSettingsAreSent() {
        val now = form.copy(author = "Bo", markSpaces = true, units = "points", overtype = true)
        assertEquals("""{"author":"Bo","units":"points","overtype":true,"marks":{"spaces":true}}""", now.changesFrom(form).toString())
    }

    @Test fun clearingATextSettingGivesItBackToTheDefault() {
        val now = form.copy(userTemplatesFolder = "  ", region = "")
        assertEquals("""{"region":"auto","userTemplatesFolder":"auto"}""", now.changesFrom(form).toString())
    }

    @Test fun panesWithNoSettingsOpenGeneral() {
        assertEquals(OptionsPane.Proofing, OptionsPane.forId("proofing"))
        assertEquals(OptionsPane.General, OptionsPane.forId("accessibility"))
        assertEquals(OptionsPane.General, OptionsPane.forId(null))
    }

    @Test fun nothingReadableIsNull() = assertNull(parseOptions(null))
}
