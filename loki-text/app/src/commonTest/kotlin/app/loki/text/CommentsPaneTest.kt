package app.loki.text

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommentsPaneTest {
    private val cards = parseComments(Json.parseToJsonElement("""[
        {"anchor":{"off":9,"path":[0],"story":"body"},"author":"Ann","date":"2026-11-08T14:30:07Z","id":0,"parent":null,"resolved":false,"text":"First"},
        {"anchor":null,"author":"Bob","date":"2026-11-08T15:00:00Z","id":1,"parent":0,"resolved":false,"text":"Reply"},
        {"anchor":{"off":3,"path":[2],"story":"body"},"author":"Ann","date":"x","id":2,"parent":null,"resolved":true,"text":"Second"},
        {"anchor":null,"author":"Cy","date":"","id":3,"parent":1,"resolved":false,"text":"Reply to reply"},
        {"id":4,"parent":99,"text":"Orphan"},{"author":"no id"},"x"]"""))

    @Test
    fun parsesAndSkipsBadRows() {
        assertEquals(listOf(0, 1, 2, 3, 4), cards.map { it.id })
        assertNull(cards[0].parent)
        assertEquals(0, cards[1].parent)
        assertTrue(cards[2].resolved)
        assertNull(cards[1].anchor)
        assertEquals(0, parseComments(null).size)
    }

    @Test
    fun groupsRepliesUnderTheirRoot() {
        val ts = commentThreads(cards)
        assertEquals(listOf(0, 2, 4), ts.map { it.root.id })
        // A reply to a reply joins the root's thread, in order.
        assertEquals(listOf(1, 3), ts[0].replies.map { it.id })
        assertEquals(0, ts[1].replies.size)
    }

    @Test
    fun aCycleDoesNotHang() {
        val loop = parseComments(Json.parseToJsonElement("""[{"id":1,"parent":2,"text":"a"},{"id":2,"parent":1,"text":"b"}]"""))
        // Whatever it makes of malformed data, it returns.
        commentThreads(loop)
    }

    @Test
    fun dates() {
        assertEquals("2026-11-08 14:30 UTC", commentDate("2026-11-08T14:30:07Z"))
        assertEquals("2026-11-08 14:30", commentDate("2026-11-08T14:30:07"))
        assertEquals("yesterday", commentDate("yesterday"))
    }
}
