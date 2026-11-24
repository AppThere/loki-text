package app.loki.text

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadAloudTest {
    @Test fun readsTheSentenceAndWhereItIs() {
        val s = parseReadAloud(Json.parseToJsonElement("""{"count":3,"current":{"end":{"off":45},"start":{"off":21},"text":"Second sentence follows."},"error":null,"index":1,"open":true,"rate":1.5,"state":"playing"}"""))!!
        assertTrue(s.playing)
        assertEquals("Second sentence follows.", s.text)
        assertEquals(1.5f, s.rate)
        assertFalse(s.last)
        assertTrue(s.copy(index = 2).last, "the third of three is the last")
    }

    @Test fun stoppedOrPausedIsNotPlaying() {
        val stopped = parseReadAloud(Json.parseToJsonElement("""{"count":3,"current":null,"index":1,"open":false,"rate":1.0,"state":"stopped"}"""))!!
        assertFalse(stopped.playing)
        assertNull(stopped.text)
        assertFalse(parseReadAloud(Json.parseToJsonElement("""{"open":true,"state":"paused","count":2}"""))!!.playing)
        assertNull(parseReadAloud(null))
    }

    @Test fun readAloudCommandsAreRecognised() {
        assertTrue(isReadAloud("review.readAloud") && isReadAloud("readAloud.next"))
        assertFalse(isReadAloud("review.spelling"))
    }
}
