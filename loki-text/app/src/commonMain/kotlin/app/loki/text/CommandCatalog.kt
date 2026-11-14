package app.loki.text

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One engine command as `engine.commands` lists it. The ribbon shows the ones on its tabs; the
 * all-commands list shows every one, so a command is reachable whatever its [location].
 */
class CommandInfo(
    val id: String,
    val label: String,
    /** `Tab › Group[ › …]`, or a place outside the ribbon (`File › Info`, `Navigation`). */
    val location: String,
    /** The params doc, in the engine's words. */
    val params: String,
    val shortcut: String,
    /** Does it change the document? One that doesn't may answer with something to read. */
    val mutates: Boolean,
) {
    val path: List<String> get() = location.split(" › ")
    val tab: String get() = path.firstOrNull().orEmpty()
    val group: String get() = path.getOrNull(1).orEmpty()

    /** Can't run from a plain button: a control or the form has to supply a value. */
    val needsInput: Boolean get() = id in NEEDS_INPUT || !runsWithoutParams(params)

    /** Takes parameters at all (required or optional), so the form has something to offer. */
    val hasParams: Boolean get() = params.trim().let { it.startsWith("{") && !it.startsWith("{}") } && parseParamForm(params).fields.isNotEmpty()
}

/**
 * Params docs for commands the engine lists as taking none although they need some (read from
 * what the engine reports when they are run bare), so their forms have fields.
 */
internal val PARAM_DOCS = mapOf(
    "styles.addToGallery" to """{"style": string (name or id), "value"?: bool (false takes it out of the gallery)}""",
    "design.themeColors" to """{"name": "Craft|Studio|Gallery|Atelier|Darkroom|Sketchbook"}""",
)

/** Every command of `engine.commands`, in the engine's order; entries that are not usable are left out. */
fun parseCommands(commands: JsonElement?): List<CommandInfo> = (commands as? JsonArray).orEmpty().mapNotNull { c ->
    val o = c as? JsonObject ?: return@mapNotNull null
    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val id = str("id")
    // `ui.*` commands act on the egui front end's own window.
    if (id.isEmpty() || id.startsWith("ui.")) return@mapNotNull null
    CommandInfo(id, str("label").ifEmpty { id }, str("location"), PARAM_DOCS[id] ?: str("params"), str("shortcut"), (o["mutates"] as? JsonPrimitive)?.booleanOrNull != false)
}

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

/** Should [c] appear in menus and the all-commands list? */
internal fun isListed(c: CommandInfo): Boolean =
    c.id !in POINTER_GESTURES && c.id !in POINTER_GAPS && c.id !in TYPING_COMMANDS && c.id !in STATE_READERS

/** The places outside the ribbon's tabs, in the order the all-commands list shows them after the tabs. */
internal val OTHER_PLACES = listOf("File", "Quick Access Toolbar", "Editing", "Navigation", "Reading", "Status Bar", "Title Bar", "Zotero", "Agents")

/** The all-commands list's sections for [commands]: the ribbon's tabs, then the other places, then any place not known yet. */
internal fun catalogSections(commands: List<CommandInfo>): List<String> {
    val present = commands.filter(::isListed).map { it.tab.ifEmpty { "Other" } }.toSet()
    val known = TAB_ORDER + CONTEXTUAL_ORDER + OTHER_PLACES
    return known.filter { it in present } + (present - known.toSet()).sorted()
}

/**
 * Is a command's answer something to show? Not a selection (most commands answer with where the
 * caret went) and not a toggle's new value: a list, or an object with something in it to read.
 */
internal fun isInformative(result: JsonElement?): Boolean = when (result) {
    null, is JsonNull -> false
    is JsonPrimitive -> false
    is JsonArray -> true
    is JsonObject -> when {
        "anchor" in result && "focus" in result -> false
        result.size <= 2 && result.values.all { it is JsonPrimitive } -> false
        else -> true
    }
}

/** Commands whose answer is the app's own state, polled or drawn elsewhere; never shown as a result. */
internal val QUIET_RESULTS = setOf(
    "view.state", "format.state", "para.state", "nav.headings", "nav.search", "file.save", "edit.copy", "edit.cut", "equation.gallery", "styles.manage",
    "edit.clipboardItems", "edit.clearClipboard", "review.comments", "review.changes", "arrange.selectionPane",
    "review.readAloud", "tools.recordMacro", "draw.addPen", "review.suggestions", "review.thesaurus",
)

/** Should the answer of [c] be shown to the person who ran it from a menu? */
internal fun showsResult(c: CommandInfo, result: JsonElement?): Boolean =
    !c.mutates && c.id !in QUIET_RESULTS && !c.id.startsWith("draw.") && !c.id.startsWith("readAloud.") && isInformative(result)

/** One line of a result: how deep it is nested, its name (empty for a list item) and its value (empty for a heading). */
class ResultLine(val depth: Int, val name: String, val value: String)

/** The most lines a result shows; a document dump can be huge. */
private const val MAX_RESULT_LINES = 400
private const val MAX_RESULT_TEXT = 2000

private fun scalarText(p: JsonPrimitive): String = when {
    p is JsonNull -> "—"
    p.isString -> p.content.take(MAX_RESULT_TEXT)
    p.booleanOrNull == true -> "Yes"
    p.booleanOrNull == false -> "No"
    else -> p.content
}

/**
 * A command's answer as lines a person can read: names made readable, nesting as indentation,
 * booleans as Yes and No. Long answers are cut at [MAX_RESULT_LINES] with a line that says so.
 */
fun describeResult(result: JsonElement?): List<ResultLine> {
    val out = ArrayList<ResultLine>()
    fun walk(name: String, e: JsonElement, depth: Int) {
        if (out.size > MAX_RESULT_LINES || depth > 12) return
        when (e) {
            is JsonPrimitive -> out += ResultLine(depth, name, scalarText(e))
            is JsonArray -> {
                if (e.isEmpty()) { out += ResultLine(depth, name, "None"); return }
                // A list of plain values reads best on one line.
                if (e.all { it is JsonPrimitive } && e.size <= 12) { out += ResultLine(depth, name, e.joinToString(", ") { scalarText(it.jsonPrimitive) }); return }
                if (name.isNotEmpty()) out += ResultLine(depth, name, "")
                e.forEachIndexed { i, item ->
                    if (item is JsonPrimitive) walk("", item, depth + 1)
                    else { out += ResultLine(depth + 1, "${i + 1}", ""); walk("", item, depth + 1) }
                }
            }
            is JsonObject -> {
                if (name.isNotEmpty()) out += ResultLine(depth, name, "")
                val d = if (name.isNotEmpty()) depth + 1 else depth
                if (e.isEmpty() && name.isNotEmpty()) out[out.lastIndex] = ResultLine(depth, name, "None")
                for ((k, v) in e) walk(fieldLabel(k), v, d)
            }
        }
    }
    when (result) {
        null -> {}
        is JsonArray -> if (result.isEmpty()) out += ResultLine(0, "", "Nothing to show") else walk("", result, -1)
        else -> walk("", result, 0)
    }
    if (out.size > MAX_RESULT_LINES) {
        while (out.size > MAX_RESULT_LINES) out.removeAt(out.lastIndex)
        out += ResultLine(0, "", "… and more")
    }
    return out
}

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
