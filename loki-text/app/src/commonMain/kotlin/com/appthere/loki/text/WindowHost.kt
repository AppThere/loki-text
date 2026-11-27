package com.appthere.loki.text

import androidx.compose.runtime.Stable

/** Another open window of the app, as Switch Windows and View Side by Side list it. */
data class OpenWindow(val id: Int, val title: String)

/**
 * What the platform does with the app's windows, for View › Window and full screen. The desktop has several windows
 * it can move; Android has one window per document task, which the system places; [arrangeAll] and [sideBySide] say
 * why they can't when the platform doesn't let the app place windows.
 */
@Stable
interface WindowHost {
    /** Puts this window in or out of full screen. */
    fun setFullScreen(on: Boolean)

    /** The app's other open windows. */
    fun others(): List<OpenWindow>

    /** Brings window [id] to the front. */
    fun focus(id: Int)

    /** This window's title, as the others list it. */
    fun setTitle(title: String)

    /** Tiles every window on the screen; returns why it can't, or null when done. */
    fun arrangeAll(): String?

    /** Puts this window and window [other] side by side ([on]), or ends it; returns why it can't, or null when done. */
    fun sideBySide(other: Int, on: Boolean): String?

    /** Scrolls the window side by side with this one with it, while Synchronous Scrolling is on. */
    fun setSyncScroll(on: Boolean)

    /** This window scrolled to [fraction] of its length; the host passes it to the window beside it when scrolling is synchronous. */
    fun scrolled(fraction: Float)

    /** Where the window beside this one asks it to scroll (a fraction of its length), or null; changes as that window scrolls. */
    val followScroll: Float?
}

/** The `view.switchWindows`-like list: this window first, then the others, for the Switch Windows menu. */
internal fun windowChoices(thisTitle: String, others: List<OpenWindow>): List<Pair<Int?, String>> =
    listOf<Pair<Int?, String>>(null to thisTitle) + others.map { it.id to it.title }

/** A scroll fraction from a position in a length, kept within 0..1 (0 for nothing to scroll). */
internal fun scrollFraction(value: Int, max: Int): Float = if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f)
