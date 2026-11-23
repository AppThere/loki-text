package app.loki.text

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateThumbnailsTest {
    @Test fun everyBuiltInTemplateIsDrawn() {
        val pictures = runBlocking { TemplateThumbnails.load() }
        assertEquals(BUILTIN_TEMPLATES.map { it.id }.toSet(), pictures.keys)
    }
}
