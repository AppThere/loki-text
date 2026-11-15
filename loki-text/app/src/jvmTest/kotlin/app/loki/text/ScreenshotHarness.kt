package app.loki.text

import app.loki.kit.*

import app.loki.kit.runShotScript
import java.io.File
import kotlin.test.Test

/**
 * Draws the real app off screen and saves PNGs (the kit's [runShotScript] has the step language):
 *
 *     LOKI_LIB=$PWD/target/debug LOKI_SHOTS=/tmp/shots LOKI_SHOT_DOC=/path/to/sample.docx \
 *     LOKI_SHOT_SCRIPT='size=390x780@2; wait=3000; shot=phone.png; click=116,66; wait; shot=commands.png' \
 *     gradle :app:jvmTest --tests '*ScreenshotHarness*' --rerun
 *
 * `LOKI_SHOT_DOC` names a document to open at start. Without `LOKI_SHOTS` the test does nothing,
 * as it needs the native engine library (`scripts/build-desktop.sh`, or `cargo build -p loki-ffi`).
 */
class ScreenshotHarness {
    @Test
    fun runScript() {
        val dir = System.getenv("LOKI_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        System.getenv("LOKI_LIB")?.let { System.setProperty("loki.lib", it) }
        val doc = System.getenv("LOKI_SHOT_DOC")?.takeIf { it.isNotBlank() }
        runShotScript(File(dir), System.getenv("LOKI_SHOT_SCRIPT") ?: "size=1200x800@1; wait; shot=app.png") { App(initialPath = doc) }
    }
}
