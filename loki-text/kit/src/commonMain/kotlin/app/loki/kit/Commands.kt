package app.loki.kit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Can a command run from a plain button? Its params must be empty or all optional. A command that
 * needs a value (a font size, a colour) or has alternative forms needs a control or the form.
 */
fun runsWithoutParams(params: String): Boolean {
    val p = params.trim()
    // A doc that is prose from its first word ("(no params) …") describes no parameters.
    if (!p.startsWith("{")) return true
    // Only the parameters count: what the doc says the command returns (`{} → {"count": n}`) is not input.
    val form = parseParamForm(p)
    return !form.alternatives && form.fields.none { !it.optional }
}

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
    /** Can't run from a plain button: a control or the form has to supply a value. */
    val needsInput: Boolean = !runsWithoutParams(params),
) {
    val path: List<String> get() = location.split(" › ")
    val tab: String get() = path.firstOrNull().orEmpty()
    val group: String get() = path.getOrNull(1).orEmpty()

    /** Takes parameters at all (required or optional), so the form has something to offer. */
    val hasParams: Boolean get() = params.trim().let { it.startsWith("{") && !it.startsWith("{}") } && parseParamForm(params).fields.isNotEmpty()
}

/**
 * What one app tells the kit about its engine's commands. Everything else the kit does (the
 * ribbon, the forms, the all-commands list, the coverage check) is worked out from
 * `engine.commands` and this.
 */
class CommandProfile(
    /** The main ribbon tabs, in order. */
    val tabs: List<String>,
    /** Tabs that show only when the selection brings them up, in order. */
    val contextualTabs: List<String> = emptyList(),
    /** Places outside the ribbon, in the order the all-commands list shows them after the tabs. */
    val otherPlaces: List<String> = emptyList(),
    /** Commands whose optional keys carry a rule in prose ("give `tag` or `source`"), so they fail when run bare. */
    val needsInput: Set<String> = emptySet(),
    /** Params docs for commands whose engine doc omits their parameters. */
    val paramDocs: Map<String, String> = emptyMap(),
    /** Commands a press or drag on the surface issues. Not menu entries. */
    val pointerGestures: Set<String> = emptySet(),
    /** Pointer gestures the app does not have yet: the list of what is out of reach. */
    val pointerGaps: Set<String> = emptySet(),
    /** Commands the keyboard issues as part of typing. */
    val typing: Set<String> = emptySet(),
    /** Commands that only read state the app already shows. */
    val stateReaders: Set<String> = emptySet(),
    /** Commands whose answer is never shown as a result (the app draws it elsewhere). */
    val quietResults: Set<String> = emptySet(),
    /** Id prefixes whose answers are never shown (`draw.` tool state, and the like). */
    val quietPrefixes: List<String> = emptyList(),
    /** Id prefix of commands that act on another front end's window (`ui.`); left out altogether. */
    val foreignPrefix: String = "ui.",
) {
    /** Should [c] appear in menus and the all-commands list? */
    fun isListed(c: CommandInfo): Boolean = c.id !in pointerGestures && c.id !in pointerGaps && c.id !in typing && c.id !in stateReaders

    /** Is [tab] a ribbon tab (main or contextual)? */
    fun isRibbonTab(tab: String): Boolean = tab in tabs || tab in contextualTabs

    /** Should the answer of [c] be shown to the person who ran it from a menu? */
    fun showsResult(c: CommandInfo, result: JsonElement?): Boolean =
        !c.mutates && c.id !in quietResults && quietPrefixes.none { c.id.startsWith(it) } && isInformative(result)
}

/** Every command of `engine.commands`, in the engine's order; entries that are not usable are left out. */
fun parseCommands(commands: JsonElement?, profile: CommandProfile): List<CommandInfo> = (commands as? JsonArray).orEmpty().mapNotNull { c ->
    val o = c as? JsonObject ?: return@mapNotNull null
    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val id = str("id")
    if (id.isEmpty() || (profile.foreignPrefix.isNotEmpty() && id.startsWith(profile.foreignPrefix))) return@mapNotNull null
    val params = profile.paramDocs[id] ?: str("params")
    CommandInfo(
        id, str("label").ifEmpty { id }, str("location"), params, str("shortcut"),
        (o["mutates"] as? JsonPrimitive)?.booleanOrNull != false,
        needsInput = id in profile.needsInput || !runsWithoutParams(params),
    )
}

/** The all-commands list's sections for [commands]: the ribbon's tabs, then the other places, then any place not known yet. */
fun catalogSections(commands: List<CommandInfo>, profile: CommandProfile): List<String> {
    val present = commands.filter(profile::isListed).map { it.tab.ifEmpty { "Other" } }.toSet()
    val known = profile.tabs + profile.contextualTabs + profile.otherPlaces
    return known.filter { it in present } + (present - known.toSet()).sorted()
}

/** Does [c] match every word of [query]? The label, the place and the id all count. */
fun matchesQuery(c: CommandInfo, query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val hay = "${c.label} ${c.location} ${c.id}".lowercase()
    return words.all { it in hay }
}

/**
 * Is a command's answer something to show? Not a selection (most commands answer with where the
 * caret went) and not a toggle's new value: a list, or an object with something in it to read.
 */
fun isInformative(result: JsonElement?): Boolean = when (result) {
    null, is JsonNull -> false
    is JsonPrimitive -> false
    is JsonArray -> true
    is JsonObject -> when {
        "anchor" in result && "focus" in result -> false
        result.size <= 2 && result.values.all { it is JsonPrimitive } -> false
        else -> true
    }
}

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

/**
 * One ribbon command. Most run from a plain button; one that [needsInput] opens its control or the
 * command form, so menus name it with an ellipsis and it is never drawn as a bare icon.
 */
class RibbonItem(val id: String, val label: String, val shortcut: String, val needsInput: Boolean = false) {
    /** The name a menu shows: with an ellipsis when choosing it asks for something first. */
    val menuLabel: String get() = if (needsInput && !label.endsWith("…")) "$label…" else label
}

class RibbonGroup(val name: String, val items: List<RibbonItem>)

class RibbonTab(val name: String, val groups: List<RibbonGroup>)

/**
 * Builds the ribbon from `engine.commands`: `location` is `Tab › Group[ › …]`. Every listed command
 * on a ribbon tab is in its group, so none is out of reach. A group left with nothing to show is
 * dropped unless [keepEmpty] says the app draws that group itself.
 */
fun parseRibbon(commands: JsonElement?, profile: CommandProfile, keepEmpty: (tab: String, group: String) -> Boolean = { _, _ -> false }): List<RibbonTab> {
    val byTab = LinkedHashMap<String, LinkedHashMap<String, MutableList<RibbonItem>>>()
    for (c in parseCommands(commands, profile)) {
        val tab = c.tab.takeIf(profile::isRibbonTab) ?: continue
        val items = byTab.getOrPut(tab) { LinkedHashMap() }.getOrPut(c.group) { mutableListOf() }
        // A command the pointer or the keyboard drives is not something to pick from a menu.
        if (profile.isListed(c)) items.add(RibbonItem(c.id, c.label, c.shortcut, c.needsInput))
    }
    return (profile.tabs + profile.contextualTabs).mapNotNull { name ->
        // A tab left with no groups (all its commands are gestures) is not shown.
        byTab[name]?.filter { (g, items) -> items.isNotEmpty() || keepEmpty(name, g) }?.takeIf { it.isNotEmpty() }
            ?.let { groups -> RibbonTab(name, groups.map { (g, items) -> RibbonGroup(g, items) }) }
    }
}

/**
 * A shortcut as a person reads it. The engine lists every binding (`Mod+O / Mod+F12 / …`); a menu
 * has room for the first, and `Mod` is the platform's command key, shown as Ctrl.
 */
fun displayShortcut(raw: String): String = raw.substringBefore(" / ").trim().replace("Mod+", "Ctrl+")

/**
 * What is wrong with an app's coverage of its engine, as lines for a test to report: empty when
 * every command has a way to it. [commandsJson] is the engine's own `engine.commands`; [ribbon] is
 * the ribbon the app shows; [drawsGroup] says which groups the app draws itself; [named] is every
 * command id the app's own tables mention; [maxGaps] is the most pointer gaps allowed (a number
 * that may only go down).
 */
fun coverageProblems(
    commandsJson: JsonElement?,
    profile: CommandProfile,
    ribbon: List<RibbonTab>,
    drawsGroup: (tab: String, group: String) -> Boolean,
    named: Collection<String>,
    ownDialogs: Set<String>,
    maxGaps: Int,
): List<String> {
    val out = ArrayList<String>()
    val commands = parseCommands(commandsJson, profile)
    val ids = commands.map { it.id }.toSet()
    if (ids.size != commands.size) out += "duplicate command ids"
    val sections = catalogSections(commands, profile)
    for (c in commands.filter(profile::isListed)) if (c.tab.ifEmpty { "Other" } !in sections) out += "${c.id} is in no section (${c.location})"
    // The sets that take a command out of the menus must name commands the engine has.
    val unlisting = mapOf("pointerGestures" to profile.pointerGestures, "pointerGaps" to profile.pointerGaps, "typing" to profile.typing, "stateReaders" to profile.stateReaders)
    for ((name, set) in unlisting) for (id in (set - ids).sorted()) out += "$name names `$id`, which the engine does not have"
    if (profile.pointerGaps.size > maxGaps) out += "pointerGaps has ${profile.pointerGaps.size} entries; it may only shrink (was $maxGaps)"
    val onRibbon = ribbon.flatMap { t -> t.groups.flatMap { g -> g.items.map { it.id } } }.toSet()
    for (c in commands) if (profile.isListed(c) && profile.isRibbonTab(c.tab) && c.id !in onRibbon) out += "${c.id} is on tab ${c.tab} but not on the ribbon"
    for (t in ribbon) for (g in t.groups) if (g.items.isEmpty() && !drawsGroup(t.name, g.name)) out += "${t.name} › ${g.name} is empty"
    for (c in commands) {
        val form = parseParamForm(c.params)
        // The raw JSON box is a last resort: a command that needs input must get real controls.
        if (profile.isListed(c) && c.needsInput && c.id !in ownDialogs && form.fields.isEmpty()) out += "${c.id} needs input but its form has no fields (add a params doc)"
        if (form.fields.map { it.name }.distinct().size != form.fields.size) out += "${c.id} has a field twice"
        for (f in form.fields) if (f.kind == FieldKind.Choice && f.options.isEmpty()) out += "${c.id}.${f.name} is a choice with nothing to choose"
    }
    for (id in named.filterNot { it in ids }.distinct().sorted()) out += "the app names `$id`, which the engine does not have"
    return out
}

/**
 * The zoom that fits content of [contentW] × [contentH] surface units into a viewport of
 * [viewW] × [viewH] pixels, for a fit named `pageWidth`/`width`, `onePage`/`whole` or
 * `multiplePages`, given [pxPerUnitAt100] pixels per unit at 100%. Null when [fit] names no fit or
 * the sizes are not usable.
 */
fun fitZoom(fit: String, contentW: Float, contentH: Float, viewW: Float, viewH: Float, pxPerUnitAt100: Float): Float? {
    if (!(contentW > 0f && contentH > 0f && viewW > 0f && viewH > 0f && pxPerUnitAt100 > 0f)) return null
    val byWidth = viewW / (contentW * pxPerUnitAt100)
    val byHeight = viewH / (contentH * pxPerUnitAt100)
    val z = when (fit) {
        "pageWidth", "width" -> byWidth
        "onePage", "whole" -> minOf(byWidth, byHeight)
        // Two abreast would need a second column; one row at half size reads the same.
        "multiplePages" -> minOf(byWidth, byHeight) / 2f
        else -> return null
    }
    return z.takeIf { it.isFinite() && it > 0f }
}
