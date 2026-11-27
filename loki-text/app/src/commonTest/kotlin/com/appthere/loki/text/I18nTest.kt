package com.appthere.loki.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class I18nTest {
    private val catalog = """
        # Deutsch (de) interface catalog.
        	Bold	Fett
        	Save As…	Speichern unter…
        menu	Insert	Einfügen (Menü)
        @plural	{n} item|{n} items	{n} Element|{n} Elemente
        @id	language.name	Deutsch
        	Line one\nLine two	Zeile eins\nZeile zwei
        	Untranslated	
    """.trimIndent()

    @Test fun rowsAreReadWithTheirContextAndEscapes() {
        val t = parseCatalog(catalog)
        assertEquals("Fett", t["Bold"])
        assertEquals("Speichern unter…", t["Save As…"])
        assertEquals("Einfügen (Menü)", t[contextKey("menu", "Insert")])
        assertNull(t["Insert"], "a row with a context isn't the plain one")
        assertEquals("Deutsch", t[contextKey("@id", "language.name")])
        assertEquals("Zeile eins\nZeile zwei", t["Line one\nLine two"])
        assertNull(t["Untranslated"], "an empty translation is no translation: English shows")
        assertNull(t["{n} item|{n} items"])
    }

    @Test fun theSystemsLanguageFindsTheNearestCatalog() {
        val have = listOf("de", "pt", "pt-br", "zh-hans")
        assertEquals("pt-br", pickLanguage("pt-BR", have))
        assertEquals("pt", pickLanguage("pt-PT", have))
        assertEquals("de", pickLanguage("de_AT", have))
        assertNull(pickLanguage("en-GB", have), "English needs no catalog")
        assertNull(pickLanguage("sw-KE", have), "no catalog: English")
    }

    @Test fun aStringWithoutATranslationShowsInEnglish() = assertEquals("Not in any catalog", tr("Not in any catalog"))

    @Test fun placeholdersAreFilledInEnglishToo() = assertEquals("Page 2 of 5", trf("Page {page} of {pages}", "page" to 2, "pages" to 5))
}
