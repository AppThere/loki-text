package app.loki.text

import app.loki.kit.*

import app.loki.kit.CommandInfo
import app.loki.kit.CommandProfile
import kotlinx.serialization.json.JsonElement

/**
 * Params docs for commands the engine lists as taking none although they need some (read from
 * what the engine reports when they are run bare), so their forms have fields.
 */
internal val PARAM_DOCS = mapOf(
    "styles.addToGallery" to """{"style": string (name or id), "value"?: bool (false takes it out of the gallery)}""",
    "design.themeColors" to """{"name": "Craft|Studio|Gallery|Atelier|Darkroom|Sketchbook"}""",
)

/**
 * Commands a pointer drives: their parameters are page positions that only a press or a drag on
 * the page can supply, so a form for them would be noise. The page's own gestures issue the ones
 * in [POINTER_GESTURES]; the rest are gestures this front end doesn't have yet ([POINTER_GAPS]),
 * each with ribbon controls that reach the same result.
 */
internal val POINTER_GESTURES = setOf(
    "caret.set", "select.range", "select.objectAt", "select.grabEnd", "hf.enterAt",
    "table.draw", "table.eraser", "draw.stroke", "draw.erase",
)

/** Mouse gestures of the desktop front end with no counterpart here yet; see `docs/parity.md`. */
internal val POINTER_GAPS = setOf(
    "select.dragUnit", "select.column", "table.resizeDrag", "table.selectAt", "table.insertAt",
    "table.moveDrag", "table.fitColumn", "arrange.copyTo", "shape.link", "shape.connect",
)

/**
 * Commands that only make sense as part of typing or of a control that tracks its own state, so
 * they are not listed as something to run: the keyboard and the equation editor issue them.
 */
internal val TYPING_COMMANDS = setOf(
    "text.insert", "text.typeCode", "equation.type", "equation.move", "equation.deleteSurrounding", "equation.edit",
)

/**
 * Commands that only read the state this app already shows (the ribbon's toggles, the panes, the
 * galleries): running one from a menu would change nothing and show nothing new.
 */
internal val STATE_READERS = setOf(
    "view.state", "format.state", "para.state", "outline.state", "readAloud.status", "nav.headings", "nav.search",
    "equation.gallery", "equation.get", "equation.structureActions", "edit.clipboardItems", "styles.list", "link.targets",
    "fields.selected", "review.suggestions",
)

/** The places outside the ribbon's tabs, in the order the all-commands list shows them after the tabs. */
internal val OTHER_PLACES = listOf("File", "Quick Access Toolbar", "Editing", "Navigation", "Reading", "Status Bar", "Title Bar", "Zotero", "Agents")

/** Commands whose answer is the app's own state, polled or drawn elsewhere; never shown as a result. */
internal val QUIET_RESULTS = setOf(
    "view.state", "format.state", "para.state", "nav.headings", "nav.search", "file.save", "edit.copy", "edit.cut", "equation.gallery", "styles.manage",
    "edit.clipboardItems", "edit.clearClipboard", "review.comments", "review.changes", "arrange.selectionPane",
    "review.readAloud", "tools.recordMacro", "draw.addPen", "review.suggestions", "review.thesaurus",
)

/**
 * What Loki Text tells the shared kit about WordCraft's commands: the ribbon's tabs, the places
 * outside it, and the named sets above. The kit works the ribbon, the forms, the all-commands list
 * and the coverage check out of `engine.commands` and this.
 */
internal val LOKI_PROFILE = CommandProfile(
    tabs = TAB_ORDER,
    contextualTabs = CONTEXTUAL_ORDER,
    otherPlaces = OTHER_PLACES,
    needsInput = NEEDS_INPUT,
    paramDocs = PARAM_DOCS,
    pointerGestures = POINTER_GESTURES,
    pointerGaps = POINTER_GAPS,
    typing = TYPING_COMMANDS,
    stateReaders = STATE_READERS,
    quietResults = QUIET_RESULTS,
    quietPrefixes = listOf("draw.", "readAloud."),
)

/** Every command of `engine.commands`, read with [LOKI_PROFILE]. */
fun parseCommands(commands: JsonElement?): List<CommandInfo> = app.loki.kit.parseCommands(commands, LOKI_PROFILE)

/** Should [c] appear in menus and the all-commands list? */
internal fun isListed(c: CommandInfo): Boolean = LOKI_PROFILE.isListed(c)

/** The all-commands list's sections for [commands]. */
internal fun catalogSections(commands: List<CommandInfo>): List<String> = app.loki.kit.catalogSections(commands, LOKI_PROFILE)

/** Should the answer of [c] be shown to the person who ran it from a menu? */
internal fun showsResult(c: CommandInfo, result: JsonElement?): Boolean = LOKI_PROFILE.showsResult(c, result)

/** Does a command write the file its `path` names (so the picker asks where to save), rather than read it? */
internal fun writesFile(id: String): Boolean = id.startsWith("file.export") || id.startsWith("file.save") || id == "file.printPdf"

/** Commands this app answers with a dialog, bar or pane of its own when they are run without parameters. */
internal val APP_DIALOGS = setOf(
    "format.fontDialog", "para.dialog", "layout.pageSetup", "styles.manage", "styles.create", "styles.modify",
    "edit.find", "edit.advancedFind", "edit.findReplace", "edit.goto", "edit.findNext", "edit.findPrevious",
    "list.define", "para.defineBullet", "para.defineNumber", "para.setNumberingValue", "review.comments",
)

/** Commands that go through the system clipboard on the way. */
internal val CLIPBOARD_COMMANDS = setOf("edit.copy", "edit.cut", "edit.paste")

/**
 * The dialogs the engine asks a front end to open (`{"open": name}` from `loki.uiRequests`), as the
 * command that takes what the dialog would collect: this app opens its own dialog for the ones in
 * [APP_DIALOGS] and the command form for the rest.
 */
internal val REQUEST_DIALOGS = mapOf(
    "font" to "format.fontDialog", "paragraph" to "para.dialog", "pageSetup" to "layout.pageSetup", "manageStyles" to "styles.manage",
    "zoom" to "view.zoom", "options" to "file.options", "customizeKeyboard" to "tools.customizeKeyboard", "customizeRibbon" to "tools.customizeRibbon",
    "link" to "insert.link", "insertPicture" to "insert.picture", "insertChart" to "insert.chart", "icons" to "insert.icon",
    "phonetic" to "format.phonetic", "addFont" to "font.add",
)
