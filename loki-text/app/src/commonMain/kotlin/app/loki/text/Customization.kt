package app.loki.text

import app.loki.kit.Chord
import app.loki.kit.CommandInfo
import app.loki.kit.RibbonGroup
import app.loki.kit.RibbonItem
import app.loki.kit.RibbonTab
import app.loki.kit.parseChord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One tab of the person's ribbon layout: a built-in tab (maybe hidden, maybe with groups of their own) or a tab they made. */
internal data class LayoutTab(val name: String, val custom: Boolean, val hidden: Boolean, val groups: List<Pair<String, List<String>>>)

/** The ribbon and Quick Access Toolbar as `tools.customizeRibbon` reports them: the tabs in order, and the toolbar's commands. */
internal data class RibbonLayout(val tabs: List<LayoutTab>, val qat: List<String>)

internal fun parseRibbonLayout(r: JsonElement?): RibbonLayout? {
    val o = r as? JsonObject ?: return null
    fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val tabs = (o["tabs"] as? JsonArray ?: return null).mapNotNull { e ->
        val t = e as? JsonObject ?: return@mapNotNull null
        val groups = (t["groups"] as? JsonArray).orEmpty().mapNotNull { g ->
            val go = g as? JsonObject ?: return@mapNotNull null
            (str(go["name"]) ?: return@mapNotNull null) to (go["commands"] as? JsonArray).orEmpty().mapNotNull { str(it) }
        }
        LayoutTab(
            str(t["name"]) ?: return@mapNotNull null,
            (t["custom"] as? JsonPrimitive)?.booleanOrNull == true,
            (t["hidden"] as? JsonPrimitive)?.booleanOrNull == true,
            groups,
        )
    }
    return RibbonLayout(tabs, (o["qat"] as? JsonArray).orEmpty().mapNotNull { str(it) })
}

/** The layout as `tools.customizeRibbon`'s `layout` takes it back. */
internal fun RibbonLayout.toJson(): JsonObject = buildJsonObject {
    put("qat", buildJsonArray { qat.forEach { add(JsonPrimitive(it)) } })
    put("tabs", buildJsonArray {
        for (t in tabs) add(buildJsonObject {
            put("name", t.name)
            if (t.custom) put("custom", true)
            if (t.hidden) put("hidden", true)
            if (t.groups.isNotEmpty() || t.custom) put("groups", buildJsonArray {
                for ((name, ids) in t.groups) add(buildJsonObject {
                    put("name", name)
                    put("commands", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                })
            })
        })
    })
}

/**
 * The ribbon the person laid out: [base] (the engine's tabs as this app draws them) in the layout's order, hidden
 * tabs left out, their own groups added to the end of a tab, and tabs they made. Tabs the layout doesn't name (the
 * tool tabs that appear with a table or a picture) keep their place after the rest. Commands the engine doesn't
 * have, and groups left with none, are left out.
 */
internal fun applyRibbonLayout(base: List<RibbonTab>, layout: RibbonLayout, catalog: Map<String, CommandInfo>): List<RibbonTab> {
    val byName = base.associateBy { it.name }
    fun groupsOf(t: LayoutTab) = t.groups.mapNotNull { (name, ids) ->
        val items = ids.mapNotNull { id -> catalog[id]?.let { RibbonItem(it.id, it.label, it.shortcut, it.needsInput) } }
        if (items.isEmpty()) null else RibbonGroup(name, items)
    }
    val named = layout.tabs.map { it.name }.toSet()
    val laidOut = layout.tabs.filter { !it.hidden }.mapNotNull { t ->
        val builtIn = byName[t.name]
        when {
            t.custom -> RibbonTab(t.name, groupsOf(t)).takeIf { it.groups.isNotEmpty() }
            builtIn != null -> RibbonTab(builtIn.name, builtIn.groups + groupsOf(t))
            else -> null
        }
    }
    return laidOut + base.filter { it.name !in named }
}

/**
 * The shortcuts with the person's key changes on top: each changed command keeps only the keys it has now, and a
 * key given to it is taken from whatever had it (as the engine does).
 */
internal fun applyKeyOverrides(base: Map<Chord, String>, custom: KeyCustom): Map<Chord, String> {
    val out = LinkedHashMap(base)
    val changed = custom.commands.map { it.first }.toSet()
    out.entries.removeAll { it.value in changed }
    for ((id, keys) in custom.commands) for (k in keys) parseChord(k)?.let { out[it] = id }
    return out
}

/**
 * The `tools.customizeKeyboard` calls that make a fresh engine's keys what [custom] says, given each command's
 * built-in keys ([defaults], as `engine.commands` lists them). Keys are taken away first, so a key moving from one
 * command to another is free when it is given.
 */
internal fun keyReplay(custom: KeyCustom, defaults: Map<String, List<String>>): List<JsonObject> {
    fun same(a: String, b: String) = parseChord(a)?.let { it == parseChord(b) } ?: a.equals(b, ignoreCase = true)
    val removes = ArrayList<JsonObject>()
    val assigns = ArrayList<JsonObject>()
    for ((id, keys) in custom.commands) {
        val builtIn = defaults[id].orEmpty()
        for (k in builtIn) if (keys.none { same(it, k) }) removes += removeParams(id, k)
        for (k in keys) if (builtIn.none { same(it, k) }) assigns += assignParams(id, k)
    }
    return removes + assigns
}

/** Each command's built-in keys, from the catalog's shortcut text (`Mod+O / Mod+F12`). */
internal fun builtInKeys(catalog: Collection<CommandInfo>): Map<String, List<String>> =
    catalog.filter { it.shortcut.isNotBlank() }.associate { c -> c.id to c.shortcut.split(" / ").map { it.trim() }.filter { it.isNotEmpty() } }

/** [custom] as the settings file keeps it (the engine's `{command, keys}` list). */
internal fun KeyCustom.toJson(): JsonArray = buildJsonArray {
    for ((id, keys) in commands) add(buildJsonObject { put("command", id); put("keys", buildJsonArray { keys.forEach { add(JsonPrimitive(it)) } }) })
}
