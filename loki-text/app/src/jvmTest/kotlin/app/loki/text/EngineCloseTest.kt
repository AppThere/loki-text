package app.loki.text

import app.loki.text.engine.NativeEngine
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A window can close while a render or a call is running on another thread. Closing waits for it, and calls after close
 * answer with an error instead of touching a freed session (which corrupted the heap and crashed the app on Android).
 */
class EngineCloseTest {
    @Test fun closingWhileCallsRunIsSafe() {
        repeat(20) {
            val e = NativeEngine()
            e.callRaw("engine.execute", """{"command":"text.insert","params":{"text":"Some words to lay out and draw."}}""")
            val busy = thread {
                repeat(30) {
                    e.renderPage(0, 0.5f)
                    e.callRaw("document.inspect", null)
                }
            }
            Thread.sleep(2)
            e.close()
            busy.join()
            // Once closed, every call is a clean refusal.
            assertTrue(e.callRaw("document.inspect", null).contains("engine closed"))
            assertTrue(e.renderPage(0, 1f) == null)
            e.close()
        }
    }
}
