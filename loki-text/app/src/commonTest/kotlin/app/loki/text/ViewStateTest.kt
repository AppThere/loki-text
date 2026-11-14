package app.loki.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewStateTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun theViewStateCarriesZoomModePanesAndWhatThePageShows() {
        val v = parseViewState(json("""{"mode":"web","readMode":false,"zoom":1.5,"fit":"","darkMode":true,"ruler":true,"gridlines":true,"navPane":true,
            "commentsPane":true,"stylesPane":false,"clipboardPane":true,"accessibilityPane":false,"draw":{"mode":"select"}}"""))
        assertTrue(v.flat && v.darkPages && v.ruler && v.gridlines && v.navPane)
        assertEquals(1.5f, v.zoom)
        assertEquals(listOf(PaneKind.Comments, PaneKind.Clipboard), v.panes)
        assertNull(v.ink)
        // Read Mode is pages, whatever the mode underneath.
        assertFalse(parseViewState(json("""{"mode":"web","readMode":true}""")).flat)
    }

    @Test
    fun aBrokenViewStateIsTheDefaultOne() {
        for (bad in listOf("null", "[]", "7", """{"zoom":"big","mode":3,"fit":null,"draw":[1]}""", """{"zoom":-2}""", """{"zoom":0}""")) {
            val v = parseViewState(json(bad))
            assertEquals(1f, v.zoom, bad)
            assertEquals("print", v.mode, bad)
            assertTrue(v.panes.isEmpty() && v.ink == null && v.fit.isEmpty(), bad)
        }
        assertEquals("print", parseViewState(null).mode)
    }

    @Test
    fun theToolInHandComesWithItsInk() {
        val pen = parseViewState(json("""{"draw":{"mode":{"pen":"highlighter"},"highlighter":{"color":[255,255,0],"width":12.0},"pen":{"color":[0,0,0],"width":1.5}}}""")).ink
        assertNotNull(pen)
        assertEquals("highlighter", pen.name)
        assertEquals(listOf(255, 255, 0), listOf(pen.red, pen.green, pen.blue))
        assertEquals(12f, pen.width)
        assertTrue(pen.draws)
        assertFalse(parseViewState(json("""{"draw":{"mode":"eraser"}}""")).ink!!.draws)
        assertNull(parseViewState(json("""{"draw":{"mode":"select"}}""")).ink)
        assertNull(parseViewState(json("""{"draw":{"mode":{"pen":"crayon"}}}""")).ink)
        // Odd settings fall back to a plain pen rather than failing.
        assertEquals(1.5f, parseViewState(json("""{"draw":{"mode":{"pen":"pen"},"pen":{"color":"red","width":-4}}}""")).ink!!.width)
    }

    @Test
    fun inkGesturesBecomeTheCommandTheToolMeans() {
        val pts = listOf(10f to 10f, 20f to 15f, 30f to 10f)
        val (stroke, sp) = inkCommand(InkTool("pen"), 2, pts)!!
        assertEquals("draw.stroke", stroke)
        assertEquals(2, sp["page"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, sp["points"]!!.jsonArray.size)
        assertEquals("draw.erase", inkCommand(InkTool("eraser"), 0, pts.take(1))!!.first)
        assertEquals("draw.lasso", inkCommand(InkTool("lasso"), 0, pts)!!.first)
        // A tap is not a stroke or a loop, and nothing unusable is sent.
        assertNull(inkCommand(InkTool("pen"), 0, pts.take(1)))
        assertNull(inkCommand(InkTool("lasso"), 0, pts.take(2)))
        assertNull(inkCommand(InkTool("pen"), 0, listOf(Float.NaN to 1f)))
        val long = inkCommand(InkTool("pen"), 0, List(9000) { it.toFloat() to 1f })!!.second
        assertEquals(MAX_INK_POINTS, long["points"]!!.jsonArray.size)
    }

    @Test
    fun fitsAreWorkedOutFromThePageAndTheWindow() {
        // A Letter page (612 × 792 pt) at 1.5 px per point in a 918 × 594 px area.
        assertEquals(1f, fitZoom("pageWidth", 612f, 792f, 918f, 594f, 1.5f)!!, 0.001f)
        assertEquals(0.5f, fitZoom("onePage", 612f, 792f, 918f, 594f, 1.5f)!!, 0.001f)
        assertEquals(0.25f, fitZoom("multiplePages", 612f, 792f, 918f, 594f, 1.5f)!!, 0.001f)
        assertNull(fitZoom("", 612f, 792f, 918f, 594f, 1.5f))
        assertNull(fitZoom("pageWidth", 0f, 792f, 918f, 594f, 1.5f))
        assertNull(fitZoom("pageWidth", 612f, 792f, Float.NaN, 594f, 1.5f))
        assertNull(fitZoom("onePage", 612f, 792f, 918f, -3f, 1.5f))
    }

    @Test
    fun paperColoursAreTints() {
        assertNotNull(paperTint("cream"))
        assertNotNull(paperTint("sepia"))
        assertNull(paperTint("document"))
        assertNull(paperTint(null))
    }

    @Test
    fun theRulerCountsEighthsOfAnInchFromTheLeftMargin() {
        val info = parseRulerInfo(json("""{"pageW":612.0,"marginLeft":72.0,"marginRight":72.0}"""))!!
        val ticks = rulerTicks(info)
        assertEquals(0f, ticks.first().x, 0.01f)
        // The margin is zero: an inch mark with no number; one inch into the text reads 1.
        val zero = ticks.first { kotlin.math.abs(it.x - 72f) < 0.01f }
        assertEquals(2, zero.size)
        assertEquals("", zero.label)
        assertEquals("1", ticks.first { kotlin.math.abs(it.x - 144f) < 0.01f }.label)
        assertEquals("1", ticks.first { it.x < 0.01f }.label)
        assertTrue(ticks.last().x <= 612f)
        for (bad in listOf("null", "{}", """{"pageW":0,"marginLeft":1,"marginRight":1}""", """{"pageW":100,"marginLeft":60,"marginRight":60}""", """{"pageW":"x"}"""))
            assertNull(parseRulerInfo(json(bad)), bad)
    }

    @Test
    fun panesListWhatTheEngineAnswers() {
        val clip = parsePane(PaneKind.Clipboard, json("""[{"chars":19,"index":0,"objects":0,"preview":"Hello"},{"preview":"no index"},5]"""))
        assertEquals(listOf("Hello"), clip.rows.map { it.title })
        assertEquals("edit.pasteClipboardItem", clip.rows.single().tap!!.steps.single().command)
        assertEquals("edit.deleteClipboardItem", clip.rows.single().actions.single().steps.single().command)

        val changes = parsePane(PaneKind.Reviewing, json("""[{"author":"Ann","date":"2026-11-14T00:58:55Z","kind":"insert","text":" added",
            "start":{"off":19,"path":[0],"story":"body"},"end":{"off":25,"path":[0],"story":"body"}},{"kind":"delete","text":"no range"}]"""))
        val row = changes.rows.single()
        assertEquals("Inserted:  added", row.title)
        assertEquals("Ann · 2026-11-14", row.detail)
        // Accept goes to the change first, then accepts it.
        assertEquals(listOf("select.range", "review.accept"), row.actions.first().steps.map { it.command })
        assertEquals(listOf("Accept All", "Reject All"), changes.header.map { it.label })

        val styles = parsePane(PaneKind.Styles, json("""[{"id":"Heading1","kind":"paragraph","name":"Heading 1"},{"name":"no id"}]"""))
        assertEquals("Heading1", (styles.rows.single().tap!!.steps.single().params["style"])!!.jsonPrimitive.content)
        assertEquals("styles.manage", styles.header.last().ui)

        val inspect = parsePane(PaneKind.StyleInspector, json("""{"character":{"direct":["bold"],"style":null,"styleName":null},"paragraph":{"direct":[],"style":"Normal","styleName":"Normal"}}"""))
        assertEquals(listOf("Normal", "None", "None", "bold"), inspect.rows.map { it.detail })
        assertEquals("characterFormatting", inspect.rows.last().actions.single().steps.single().params["level"]!!.jsonPrimitive.content)

        val a11y = parsePane(PaneKind.Accessibility, json("""{"issues":[{"fix":"file.properties {title}","issue":"Document has no title","kind":"tip"}],"ok":false}"""))
        assertEquals("Document has no title", a11y.rows.single().title)

        val objects = parsePane(PaneKind.Selection, json("""[{"hidden":true,"index":0,"kind":"shape","name":"TextBox 1"}]"""))
        assertEquals("Shape · hidden", objects.rows.single().detail)

        for (kind in PaneKind.entries) for (bad in listOf("null", "7", "{}", "[]", """[{"a":1}]""", """{"issues":7}"""))
            assertTrue(parsePane(kind, json(bad)).rows.size <= 4, "$kind $bad")
        assertTrue(altTextPane(null, "nothing selected").rows.isEmpty())
        assertEquals(listOf("A cat", "None", "No"), altTextPane(PictureState(10f, 10f, listOf(0f, 0f, 0f, 0f), "A cat", "", false), null).rows.map { it.detail })
    }
}
