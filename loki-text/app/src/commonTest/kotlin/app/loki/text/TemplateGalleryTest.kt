package app.loki.text

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemplateGalleryTest {
    @Test fun builtInsMakeFileNewWithTheirId() {
        val (id, params) = BUILTIN_TEMPLATES.first { it.id == "report" }.command()
        assertEquals("file.new", id)
        assertEquals("""{"template":"report"}""", params.toString())
    }

    @Test fun fileTemplatesReplaceABlankDocument() {
        val (id, params) = TemplateChoice.File("/t/memo.dotx", "Memo").command()
        assertEquals("file.newFromTemplate", id)
        assertEquals("""{"path":"/t/memo.dotx","replaceBlank":true}""", params.toString())
    }

    @Test fun aWindowIsAskedForATemplateByItsPath() {
        for (t in BUILTIN_TEMPLATES) assertEquals(t, templateOfRequest(t.asRequest()))
        val file = templateOfRequest(TemplateChoice.File("/t/memo.dotx", "Memo").asRequest())
        assertEquals(TemplateChoice.File("/t/memo.dotx", "memo"), file)
        assertNull(templateOfRequest("/docs/a.docx"))
        assertNull(templateOfRequest(null))
        assertNull(templateOfRequest("template:nonsense"))
    }

    @Test fun personalTemplatesComeFromTheEnginesListing() {
        val listing = Json.parseToJsonElement("""{"dir":"/t","templates":[
            {"label":"memo","title":"Company memo","path":"/t/memo.dotx","category":"","modified":null},
            {"label":"invoice","title":null,"path":"/t/Work/invoice.dotx","category":"Work"},
            {"label":"nopath"}, 7]}""")
        assertEquals(
            listOf(TemplateChoice.File("/t/memo.dotx", "Company memo", ""), TemplateChoice.File("/t/Work/invoice.dotx", "invoice", "Work")),
            parsePersonalTemplates(listing),
        )
        assertEquals(emptyList(), parsePersonalTemplates(null))
    }

    @Test fun searchMatchesNamesAndCategories() {
        assertEquals(listOf("Letter"), filterTemplates(BUILTIN_TEMPLATES, "lett").map { it.label })
        assertEquals(BUILTIN_TEMPLATES, filterTemplates(BUILTIN_TEMPLATES, "  "))
        val mine = listOf(TemplateChoice.File("/a", "Invoice", "Work"), TemplateChoice.File("/b", "Memo", ""))
        assertEquals(listOf("Invoice"), filterTemplates(mine, "work").map { it.label })
    }
}
