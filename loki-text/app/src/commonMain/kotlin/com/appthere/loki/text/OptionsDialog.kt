package com.appthere.loki.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The pages of File › Options this app has settings for, in the order they are listed. [id] is the engine's `pane` name. */
internal enum class OptionsPane(val id: String, val title: String) {
    General("general", "General"),
    Display("display", "Display"),
    Proofing("proofing", "Proofing"),
    Save("save", "Save"),
    Language("language", "Language"),
    Advanced("advanced", "Advanced");

    companion object {
        /** The page for an engine pane name. Panes with no settings here (Accessibility, Agents) and unknown names open General. */
        fun forId(id: String?): OptionsPane = entries.firstOrNull { it.id == id } ?: General
    }
}

/** The measurement units File › Options offers, as `file.options` names them; `auto` follows the region. */
internal val OPTION_UNITS = listOf("auto" to "Follow the region", "inches" to "Inches", "centimeters" to "Centimeters", "millimeters" to "Millimeters", "points" to "Points", "picas" to "Picas")

/** The settings File › Options edits, as `file.options` reports them. Text settings that are cleared are written back as `auto`. */
internal data class OptionsForm(
    val author: String = "", val initials: String = "",
    /** One of the names in [OPTION_UNITS]. */
    val units: String = "auto",
    /** A locale tag like pl-PL; empty follows the system. */
    val region: String = "",
    val typingReplacesSelection: Boolean = true, val insertKeyOvertype: Boolean = false, val overtype: Boolean = false,
    val markTabs: Boolean = false, val markSpaces: Boolean = false, val markParagraphs: Boolean = false, val markHidden: Boolean = false,
    val checkSpelling: Boolean = true, val markGrammar: Boolean = true,
    val ignoreUppercase: Boolean = false, val ignoreNumbers: Boolean = false, val ignoreInternet: Boolean = false,
    val dictionariesFolder: String = "", val customDictionary: String = "", val userTemplatesFolder: String = "",
) {
    /** `file.options` arguments for the settings that differ from [before]; empty when nothing changed. */
    fun changesFrom(before: OptionsForm): JsonObject = buildJsonObject {
        fun text(k: String, now: String, was: String) { if (now.trim() != was.trim()) put(k, now.trim().ifEmpty { "auto" }) }
        fun flag(k: String, now: Boolean, was: Boolean) { if (now != was) put(k, now) }
        if (author != before.author) put("author", author)
        if (initials != before.initials) put("initials", initials)
        if (units != before.units) put("units", units)
        text("region", region, before.region)
        flag("typingReplacesSelection", typingReplacesSelection, before.typingReplacesSelection)
        flag("insertKeyOvertype", insertKeyOvertype, before.insertKeyOvertype)
        flag("overtype", overtype, before.overtype)
        val marks = buildJsonObject {
            if (markTabs != before.markTabs) put("tabs", markTabs)
            if (markSpaces != before.markSpaces) put("spaces", markSpaces)
            if (markParagraphs != before.markParagraphs) put("paragraphs", markParagraphs)
            if (markHidden != before.markHidden) put("hidden", markHidden)
        }
        if (marks.isNotEmpty()) put("marks", marks)
        flag("checkSpelling", checkSpelling, before.checkSpelling)
        flag("markGrammar", markGrammar, before.markGrammar)
        flag("ignoreUppercase", ignoreUppercase, before.ignoreUppercase)
        flag("ignoreNumbers", ignoreNumbers, before.ignoreNumbers)
        flag("ignoreInternet", ignoreInternet, before.ignoreInternet)
        text("dictionariesFolder", dictionariesFolder, before.dictionariesFolder)
        text("customDictionary", customDictionary, before.customDictionary)
        text("userTemplatesFolder", userTemplatesFolder, before.userTemplatesFolder)
    }
}

/** The settings from what `file.options` answered; null when it answered nothing readable. */
internal fun parseOptions(r: JsonElement?): OptionsForm? {
    val o = r as? JsonObject ?: return null
    fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
    fun flag(k: String, default: Boolean) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: default
    val marks = o["marks"] as? JsonObject
    fun mark(k: String) = (marks?.get(k) as? JsonPrimitive)?.booleanOrNull == true
    val region = o["region"] as? JsonObject
    val tag = listOf("language", "territory").map { (region?.get(it) as? JsonPrimitive)?.contentOrNull.orEmpty() }.filter { it.isNotEmpty() }.joinToString("-")
    return OptionsForm(
        author = str("author"), initials = str("initials"),
        units = if (flag("unitsFromRegion", false)) "auto" else str("units").ifEmpty { "auto" },
        region = if (flag("regionFromSystem", true)) "" else tag,
        typingReplacesSelection = flag("typingReplacesSelection", true), insertKeyOvertype = flag("insertKeyOvertype", false), overtype = flag("overtype", false),
        markTabs = mark("tabs"), markSpaces = mark("spaces"), markParagraphs = mark("paragraphs"), markHidden = mark("hidden"),
        checkSpelling = flag("checkSpelling", true), markGrammar = flag("markGrammar", true),
        ignoreUppercase = flag("ignoreUppercase", false), ignoreNumbers = flag("ignoreNumbers", false), ignoreInternet = flag("ignoreInternet", false),
        dictionariesFolder = str("dictionariesFolder"), customDictionary = str("customDictionary"), userTemplatesFolder = str("userTemplatesFolder"),
    )
}

/**
 * File › Options as pages. A phone ([sizeClass] Compact) shows the list of pages, then the page chosen; wider windows show both side by side.
 * [onApply] gets the changed settings (not called when nothing changed). [error] is what the engine refused, shown on the page.
 */
@Composable
internal fun OptionsDialog(
    form: OptionsForm?, error: String?, initialPane: OptionsPane, sizeClass: SizeClass,
    onApply: (JsonObject) -> Unit, onDismiss: () -> Unit,
) {
    var edit by remember(form) { mutableStateOf(form ?: OptionsForm()) }
    var pane by remember(initialPane) { mutableStateOf(initialPane) }
    // On a phone the list comes first, unless a pane was asked for by name.
    var listShown by remember { mutableStateOf(sizeClass == SizeClass.Compact && initialPane == OptionsPane.General) }
    val compact = sizeClass == SizeClass.Compact
    val changes = form?.let { edit.changesFrom(it) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.padding(if (compact) 8.dp else 24.dp).widthIn(max = 860.dp).heightIn(max = 640.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Options"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                Row(Modifier.weight(1f, fill = false)) {
                    if (!compact || listShown) {
                        Column((if (compact) Modifier.fillMaxWidth() else Modifier.width(180.dp)).verticalScroll(rememberScrollState())) {
                            for (p in OptionsPane.entries) {
                                val on = p == pane && !compact
                                Text(
                                    tr(p.title),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth().selectable(on, role = Role.Tab) { pane = p; listShown = false }.padding(12.dp),
                                )
                            }
                        }
                    }
                    if (!compact || !listShown) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = if (compact) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (compact) TextButton(onClick = { listShown = true }) { Text("‹ " + tr("Options")) }
                            Text(tr(pane.title), style = MaterialTheme.typography.titleMedium)
                            if (form == null) Text(error ?: tr("Reading the settings…"))
                            else OptionsPage(pane, edit) { edit = it }
                            error?.takeIf { form != null }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val dirty = changes != null && changes.isNotEmpty()
                    if (dirty) TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
                    TextButton(onClick = { if (dirty) onApply(changes!!) else onDismiss() }) { Text(if (dirty) tr("OK") else tr("Close")) }
                }
            }
        }
    }
}

@Composable
private fun OptionsPage(pane: OptionsPane, f: OptionsForm, set: (OptionsForm) -> Unit) {
    @Composable fun field(label: String, value: String, change: (String) -> Unit, hint: String? = null) =
        OutlinedTextField(value, change, label = { Text(tr(label)) }, supportingText = hint?.let { { Text(tr(it)) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
    @Composable fun check(label: String, on: Boolean, change: (Boolean) -> Unit) =
        Row(Modifier.fillMaxWidth().toggleable(on, role = Role.Checkbox, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, null); Text(tr(label), Modifier.padding(start = 8.dp))
        }
    when (pane) {
        OptionsPane.General -> {
            field("User name", f.author, { set(f.copy(author = it)) }, "Written on comments and tracked changes")
            field("Initials", f.initials, { set(f.copy(initials = it)) })
            Text(tr("Measurement units"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            for ((id, label) in OPTION_UNITS) Row(
                Modifier.fillMaxWidth().selectable(f.units == id, role = Role.RadioButton) { set(f.copy(units = id)) },
                verticalAlignment = Alignment.CenterVertically,
            ) { RadioButton(f.units == id, null); Text(tr(label), Modifier.padding(start = 8.dp)) }
        }
        OptionsPane.Display -> {
            Text(tr("Always show these formatting marks"), style = MaterialTheme.typography.labelLarge)
            check("Tab characters", f.markTabs) { set(f.copy(markTabs = it)) }
            check("Spaces", f.markSpaces) { set(f.copy(markSpaces = it)) }
            check("Paragraph marks", f.markParagraphs) { set(f.copy(markParagraphs = it)) }
            check("Hidden text", f.markHidden) { set(f.copy(markHidden = it)) }
        }
        OptionsPane.Proofing -> {
            check("Check spelling as you type", f.checkSpelling) { set(f.copy(checkSpelling = it)) }
            check("Mark grammar errors as you type", f.markGrammar) { set(f.copy(markGrammar = it)) }
            check("Ignore words in UPPERCASE", f.ignoreUppercase) { set(f.copy(ignoreUppercase = it)) }
            check("Ignore words that contain numbers", f.ignoreNumbers) { set(f.copy(ignoreNumbers = it)) }
            check("Ignore Internet and file addresses", f.ignoreInternet) { set(f.copy(ignoreInternet = it)) }
            field("Dictionaries folder", f.dictionariesFolder, { set(f.copy(dictionariesFolder = it)) }, "A folder of Hunspell .aff and .dic files; empty uses the built-in ones")
            field("Custom dictionary file", f.customDictionary, { set(f.copy(customDictionary = it)) }, "Empty uses the default custom dictionary")
        }
        OptionsPane.Save -> field(
            "Personal templates folder", f.userTemplatesFolder, { set(f.copy(userTemplatesFolder = it)) },
            "File › New lists the templates in this folder; empty uses the app's own Templates folder",
        )
        OptionsPane.Language -> field(
            "Region", f.region, { set(f.copy(region = it)) },
            "A language and country such as pl-PL. It sets the paper File › New starts on, the default units and the decimal separator; empty follows the system",
        )
        OptionsPane.Advanced -> {
            check("Typing replaces the selected text", f.typingReplacesSelection) { set(f.copy(typingReplacesSelection = it)) }
            check("Use the Insert key to switch to overtype", f.insertKeyOvertype) { set(f.copy(insertKeyOvertype = it)) }
            check("Overtype mode", f.overtype) { set(f.copy(overtype = it)) }
        }
    }
}
