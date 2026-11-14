package app.loki.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PictureRibbonTest {
    private fun state(json: String) = parseCommandState(Json.parseToJsonElement(json))

    @Test fun noPictureWhenNoneIsSelected() {
        assertNull(state("""{"disabled":{},"on":[],"picture":null}""").picture)
        assertNull(state("""{"disabled":{},"on":[]}""").picture)
    }

    @Test fun readsTheSelectedPicture() {
        val p = state("""{"disabled":{},"on":[],"picture":{"width":144,"height":72,"crop":[0.1,0,0.2,0],"alt":"A red square","title":"Red","decorative":false}}""").picture
        assertEquals(144f, p?.width)
        assertEquals(72f, p?.height)
        assertEquals(listOf(0.1f, 0f, 0.2f, 0f), p?.crop)
        assertEquals("A red square", p?.alt)
        assertEquals("Red", p?.title)
    }

    @Test fun aBadCropReadsAsUncropped() {
        val p = state("""{"disabled":{},"on":[],"picture":{"width":0,"height":null,"crop":[0.1],"alt":""}}""").picture
        assertEquals(listOf(0f, 0f, 0f, 0f), p?.crop)
        assertNull(p?.width)
        assertNull(p?.height)
    }

    @Test fun sizeKeepsProportions() =
        assertEquals(buildJsonObject { put("width", 108f); put("lockAspect", true) }, pictureSizeParams("width", 108f))

    @Test fun cropIsPercentOfEachSide() {
        assertEquals(
            buildJsonObject { put("left", 0.1f); put("top", 0f); put("right", 0.25f); put("bottom", 0.5f) },
            cropParams(listOf("10", "0", "25%", " 50 ")),
        )
        // Past 95%, not a number, or an axis left with under 5% of the picture.
        assertNull(cropParams(listOf("96", "0", "0", "0")))
        assertNull(cropParams(listOf("x", "0", "0", "0")))
        assertNull(cropParams(listOf("50", "0", "50", "0")))
        assertNull(cropParams(listOf("0", "0", "0")))
        assertEquals("10", cropText(0.1f))
    }

    @Test fun altTextAlwaysSendsTheDescription() {
        val p = pictureAltParams("  ", "Logo", decorative = true)
        assertEquals(JsonPrimitive(""), p["text"])
        assertEquals(JsonPrimitive(true), p["decorative"])
    }

    @Test fun menusOfferWhatTheEngineTakes() {
        assertEquals("+20", signed(20))
        assertEquals("−20", signed(-20))
        assertTrue(CORRECTIONS.first().first == "Normal")
        assertTrue(COLORS.any { it.second["mode"] == JsonPrimitive("blackAndWhite") })
        for (g in PICTURE_GROUPS) assertTrue(hasCustomGroup("Picture Format", g), g)
    }
}
