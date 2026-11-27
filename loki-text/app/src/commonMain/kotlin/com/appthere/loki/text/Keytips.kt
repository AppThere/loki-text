package com.appthere.loki.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The tabs' letters, as the desktop front end gives them (Word for Windows' after Alt): Home H, Insert N, Draw D,
 * Design G, Layout P, References S, Mailings M, Review R, View W, Help Y; the table tabs JT and JL. Other tool tabs
 * take J and a digit.
 */
internal val TAB_KEYTIPS = mapOf(
    "Home" to "H", "Insert" to "N", "Draw" to "D", "Design" to "G", "Layout" to "P", "References" to "S",
    "Mailings" to "M", "Review" to "R", "View" to "W", "Help" to "Y", "Developer" to "L",
    "Table Design" to "JT", "Table Layout" to "JL",
)

/** Each shown tab's keytip: its own letters, or J and a digit for a tool tab without them. */
internal fun tabKeytips(tabs: List<String>): Map<String, String> {
    var n = 1
    return tabs.associateWith { TAB_KEYTIPS[it] ?: "J${n++}" }
}

/** Letters a control's keytip never starts with: Y and Z begin the two-letter ones, so no single letter is a prefix of them. */
private val TWO_LETTER_PREFIXES = listOf('Y', 'Z')

/**
 * Keytips for controls named [labels], in the order they are drawn: each gets the first letter of its name (then the
 * next letters of it, then any) that no control before it has; once single letters run out, Y1…Y9 and Z1…Z9. No keytip
 * is the start of another, so typing one acts at once.
 */
internal fun assignKeytips(labels: List<String>): List<String> {
    val used = HashSet<String>()
    val singles = ('A'..'Z').filter { it !in TWO_LETTER_PREFIXES } + ('0'..'9')
    val twos = TWO_LETTER_PREFIXES.flatMap { p -> ('1'..'9').map { "$p$it" } }.iterator()
    return labels.map { label ->
        val own = label.uppercase().filter { it in singles }
        val pick = (own.asSequence() + singles.asSequence()).map { it.toString() }.firstOrNull { it !in used }
            ?: (if (twos.hasNext()) twos.next() else "")
        if (pick.isNotEmpty()) used += pick
        pick
    }
}

/** Where keytips are: off, showing the tabs' letters, or showing the controls' letters on the open tab. */
enum class KeytipPhase { Off, Tabs, Controls }

/**
 * A control that can be reached by keytip: its name (which picks its letters) and what pressing it does. One with a
 * [fixed] keytip (the Quick Access Toolbar's numbers) is reached from the tabs' level, beside the tab letters.
 */
class KeytipControl(val label: String, val fixed: String? = null, val action: () -> Unit) {
    /** Whether it can be pressed now; a greyed-out control keeps its place and letter, and its keytip only ends keytips. */
    var enabled = true
}

/**
 * The keytips of one window: the phase, what has been typed of a two-letter keytip, the open tab's controls as they
 * registered (in drawing order), and the shown tabs with the way to open one (the ribbon sets both).
 */
class KeytipState {
    var phase by mutableStateOf(KeytipPhase.Off)
        private set
    var typed by mutableStateOf("")
        private set
    val controls = mutableStateListOf<KeytipControl>()
    var tabs: List<String> = emptyList()
    var openTab: (String) -> Unit = {}
    private var altArmed = false

    val tabCodes: Map<String, String> get() = tabKeytips(tabs)

    /** The open tab's controls (not the fixed ones) with their keytips. */
    private fun tabControls(): Pair<List<KeytipControl>, List<String>> {
        val list = controls.filter { it.fixed == null }
        return list to assignKeytips(list.map { it.label })
    }

    fun codeOf(c: KeytipControl): String? = when {
        c.fixed != null -> c.fixed.takeIf { phase == KeytipPhase.Tabs }
        phase != KeytipPhase.Controls -> null
        else -> tabControls().let { (list, codes) -> list.indexOf(c).takeIf { it >= 0 }?.let { codes[it] } }
    }

    fun off() { phase = KeytipPhase.Off; typed = "" }

    /** Shows the tabs' letters (as Alt does). */
    fun showTabs() { phase = KeytipPhase.Tabs; typed = "" }

    /**
     * A key in the window, before anything else sees it. Alt pressed and let go on its own (or F10) shows the tabs'
     * letters; then letters open a tab and press a control; Esc goes back a step. Returns whether the key was keytips'.
     */
    fun onKey(e: KeyEvent): Boolean {
        val alt = e.key == Key.AltLeft || e.key == Key.AltRight
        if (e.type == KeyEventType.KeyDown) {
            if (alt) { altArmed = true; return false }
            altArmed = false
            if (e.key == Key.F10 && !e.isAltPressed && !e.isCtrlPressed && !e.isShiftPressed) { toggle(); return true }
            if (phase == KeytipPhase.Off) return false
            if (e.key == Key.Escape) { if (phase == KeytipPhase.Controls) { phase = KeytipPhase.Tabs; typed = "" } else off(); return true }
            val ch = e.utf16CodePoint.toChar().uppercaseChar()
            if (e.isCtrlPressed || e.isMetaPressed || !ch.isLetterOrDigit()) { off(); return false }
            type(ch)
            return true
        }
        if (e.type == KeyEventType.KeyUp && alt) {
            val wasArmed = altArmed
            altArmed = false
            if (wasArmed) { toggle(); return true }
        }
        return false
    }

    private fun toggle() { if (phase == KeytipPhase.Off) { phase = KeytipPhase.Tabs; typed = "" } else off() }

    internal fun type(ch: Char) {
        val now = typed + ch
        when (phase) {
            KeytipPhase.Tabs -> {
                val codes = tabCodes
                codes.entries.firstOrNull { it.value == now }?.let { (tab, _) -> openTab(tab); phase = KeytipPhase.Controls; typed = ""; return }
                controls.firstOrNull { it.fixed == now }?.let { c -> off(); if (c.enabled) c.action(); return }
                if (codes.values.any { it.startsWith(now) }) typed = now else off()
            }
            KeytipPhase.Controls -> {
                val (list, codes) = tabControls()
                val i = codes.indexOf(now)
                if (i >= 0) { val c = list[i]; off(); if (c.enabled) c.action(); return }
                if (codes.any { it.isNotEmpty() && it.startsWith(now) }) typed = now else off()
            }
            KeytipPhase.Off -> {}
        }
    }
}

/** The window's keytips, for the ribbon's controls; null where there are none (dialogs, the tests). */
val LocalKeytips = staticCompositionLocalOf<KeytipState?> { null }

/**
 * Makes a ribbon control reachable by keytip while it is shown, and returns its keytip while keytips show the
 * controls' letters (null otherwise).
 */
@Composable
fun rememberKeytip(label: String, enabled: Boolean, onClick: () -> Unit, fixed: String? = null): String? {
    val state = LocalKeytips.current ?: return null
    val click by rememberUpdatedState(onClick)
    val control = remember(label, fixed) { KeytipControl(label, fixed) { click() } }
    control.enabled = enabled
    // Registered while shown, enabled or not, so the letters follow drawing order and don't move when one greys out.
    DisposableEffect(control) {
        state.controls.add(control)
        onDispose { state.controls.remove(control) }
    }
    return state.codeOf(control)
}

/** A keytip's badge over a control or a tab: the letters in a small dark box. */
@Composable
fun BoxScope.KeytipBadge(code: String?, align: Alignment = Alignment.BottomCenter) {
    if (code.isNullOrEmpty()) return
    Box(
        Modifier.align(align).background(Color(0xE6202020), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
            .semantics { contentDescription = trf("Keytip {code}", "code" to code) },
    ) { Text(code, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}
