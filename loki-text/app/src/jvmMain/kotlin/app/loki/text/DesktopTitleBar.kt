package app.loki.text

import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import java.awt.Frame

/** A custom title bar installed on a window through the JetBrains Runtime API. */
internal class InstalledTitleBar(private val bar: WindowDecorations.CustomTitleBar) {
    /** Room the system keeps at the left and right, in dp (macOS traffic lights, Windows buttons). */
    val leftInset: Float get() = runCatching { bar.leftInset }.getOrDefault(0f)
    val rightInset: Float get() = runCatching { bar.rightInset }.getOrDefault(0f)

    /** While true, the bar's area counts as client area (clicks reach the controls); false returns it to dragging. */
    fun forceHitTest(client: Boolean) { runCatching { bar.forceHitTest(client) } }
}

/**
 * Replaces [window]'s native title bar with one the app draws, [height] dp tall. Null (and the
 * native bar stays) when the JVM is not a JetBrains Runtime, the platform has no support, or
 * anything fails.
 */
internal fun installCustomTitleBar(window: Frame, height: Float): InstalledTitleBar? = try {
    if (!JBR.isWindowDecorationsSupported()) {
        println("title bar: JBR custom title bars are not supported here; using the native one")
        null
    } else {
        val decorations = JBR.getWindowDecorations()
        val bar = decorations.createCustomTitleBar()
        bar.height = height
        decorations.setCustomTitleBar(window, bar)
        println("title bar: JBR custom title bar installed (${System.getProperty("os.name")}, toolkit ${java.awt.Toolkit.getDefaultToolkit()::class.java.simpleName})")
        InstalledTitleBar(bar)
    }
} catch (e: Throwable) {
    println("title bar: JBR custom title bar failed (${e::class.simpleName}: ${e.message}); using the native one")
    null
}
