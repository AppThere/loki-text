package app.loki.text

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
    fun everyCommandIsListedOrDrivenByAGesture() {
        val listed = commands.filter(::isListed)
        val sections = catalogSections(commands)
        // The all-commands list has a section for wherever a command lives.
        for (c in listed) assertTrue(c.tab.ifEmpty { "Other" } in sections, "${c.id} is in no section (${c.location})")
        // Whatever is not listed is typed, read by the app itself, or a pointer gesture: one this app has, or a named gap.
        val unlisted = commands.filterNot(::isListed).map { it.id }.toSet()
        assertEquals(unlisted, (POINTER_GESTURES + POINTER_GAPS + TYPING_COMMANDS + STATE_READERS).intersect(ids))
        // A state reader is only hidden when the app does read it.
        assertEquals(emptyList(), (STATE_READERS - ids).sorted())
        assertEquals(POINTER_GAPS, POINTER_GAPS.intersect(ids), "a gap names a command the engine no longer has")
        assertTrue(POINTER_GAPS.size <= 10, "the list of unreachable gestures may only shrink")
    }

    @Test
    fun everyCommandOnARibbonTabIsOnTheRibbon() {
        val expected = commands.filter { isListed(it) && (it.tab in TAB_ORDER || it.tab in CONTEXTUAL_ORDER) }.map { it.id }.toSet()
        assertEquals(expected, onRibbon)
        // No tab or group is left with nothing in it unless the app draws that group itself.
        for (t in ribbon) for (g in t.groups) assertTrue(g.items.isNotEmpty() || hasCustomGroup(t.name, g.name), "${t.name} › ${g.name} is empty")
    }

    @Test
    fun everyCommandThatNeedsValuesHasAFormWithFields() {
        // The raw JSON box is a last resort: a command that needs input must get real controls.
        val bare = commands.filter { isListed(it) && it.needsInput && it.id !in APP_DIALOGS && parseParamForm(it.params).fields.isEmpty() }.map { it.id }
        assertEquals(emptyList(), bare)
        for (c in commands) {
            val form = parseParamForm(c.params)
            assertEquals(form.fields.map { it.name }.distinct(), form.fields.map { it.name }, "${c.id} has a field twice")
            // Every choice has something to choose.
            for (f in form.fields) if (f.kind == FieldKind.Choice) assertTrue(f.options.isNotEmpty(), "${c.id}.${f.name}")
            // A form with nothing filled in is either sendable or says what is missing; it never throws.
            buildParams(form, emptyMap())
        }
    }

    @Test
    fun whatTheAppNamesTheEngineHas() {
        val named = APP_DIALOGS + CLIPBOARD_COMMANDS + REQUEST_DIALOGS.values + POINTER_GESTURES + TYPING_COMMANDS + QUIET_RESULTS +
            PaneKind.entries.map { it.toggle } + PaneKind.entries.mapNotNull(::paneSource) +
            FILE_MENU.mapNotNull { it?.id }.filterNot { it.startsWith("app.") } +
            GROUP_LAUNCHERS.values + NEEDS_INPUT
        assertEquals(emptyList(), named.filterNot { it in ids }.distinct().sorted(), "the app names commands the engine does not have")
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
