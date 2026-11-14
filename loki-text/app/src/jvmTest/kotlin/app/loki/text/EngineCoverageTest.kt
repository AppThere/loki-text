package app.loki.text

import app.loki.kit.*

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parity with the engine, measured: every command the engine has must be reachable from this
 * front end. The command list is the engine's own (`engine-commands.json`, which
 * `cargo test -p loki-ffi` keeps in step with `engine.commands`), so a command added to the engine
 * shows up here; the only commands with no way to them are the named pointer gestures in
 * [POINTER_GAPS], and that list may only shrink.
 */
class EngineCoverageTest {
    // The Gradle module is the working directory.
    private val raw = Json.parseToJsonElement(File("src/jvmTest/resources/engine-commands.json").readText())
    private val commands = parseCommands(raw)
    private val ids = commands.map { it.id }.toSet()
    private val ribbon = parseRibbon(raw)
    private val onRibbon = ribbon.flatMap { t -> t.groups.flatMap { g -> g.items.map { it.id } } }.toSet()

    @Test
    fun theFixtureIsTheWholeEngine() {
        assertTrue(commands.size > 600, "only ${commands.size} commands: is the fixture stale or cut short?")
        assertEquals(commands.size, ids.size, "duplicate command ids")
    }

    @Test
    fun everyCommandHasAWayToIt() {
        // Every command id one of the app's own tables mentions must be the engine's.
        val named = APP_DIALOGS + CLIPBOARD_COMMANDS + REQUEST_DIALOGS.values + QUIET_RESULTS +
            PaneKind.entries.map { it.toggle } + PaneKind.entries.mapNotNull(::paneSource) +
            FILE_MENU.mapNotNull { it?.id }.filterNot { it.startsWith("app.") } +
            GROUP_LAUNCHERS.values + NEEDS_INPUT + PARAM_DOCS.keys
        // The list of unreachable gestures may only shrink: lower this number as they are built.
        val problems = coverageProblems(raw, LOKI_PROFILE, ribbon, ::hasCustomGroup, named, APP_DIALOGS, maxGaps = 10)
        assertEquals(emptyList(), problems, problems.joinToString("\n"))
    }

    @Test
    fun aFormWithNothingFilledInNeverThrows() {
        for (c in commands) buildParams(parseParamForm(c.params), emptyMap())
    }

    @Test
    fun paneActionsAndFormsUseCommandsAndParametersTheEngineDocuments() {
        fun params(id: String) = parseParamForm(commands.first { it.id == id }.params).fields.map { it.name }.toSet()
        assertTrue("index" in params("edit.pasteClipboardItem") && "index" in params("edit.deleteClipboardItem"))
        assertTrue("level" in params("styles.inspectorClear"))
        assertTrue("index" in params("select.objects"))
        assertTrue("value" in params("view.zoom"))
        assertTrue(params("draw.stroke").containsAll(setOf("page", "points")))
        assertTrue(params("draw.erase").containsAll(setOf("page", "x", "y")))
        for (id in listOf("file.exportPdf", "file.exportPng", "file.saveTemplate")) assertTrue("path" in params(id), id)
    }
}
