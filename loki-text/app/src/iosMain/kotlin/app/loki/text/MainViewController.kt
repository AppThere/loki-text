@file:OptIn(ExperimentalForeignApi::class)

package app.loki.text

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile
import platform.Foundation.writeToURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPrintInfo
import platform.UIKit.UIPrintInfoOutputType
import platform.UIKit.UIPrintInteractionController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

actual fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap =
    Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), rgba, width * 4).toComposeImageBitmap()

/** Called from the Xcode host app (`iosApp/`): the whole app, opening on the start screen. */
fun MainViewController(): UIViewController = ComposeUIViewController { App(startScreen = true) }

/** The view controller on top, which a picker or the print sheet is presented from. Main thread only. */
private fun topController(): UIViewController? {
    val windows = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
    var vc = (windows.firstOrNull { it.keyWindow } ?: windows.firstOrNull())?.rootViewController ?: return null
    while (true) vc = vc.presentedViewController ?: return vc
}

/** Runs [block] on the main thread and waits for the value it hands to its callback. Never call on the main thread. */
private fun <T> awaitOnMain(block: (done: (T) -> Unit) -> Unit): T? {
    if (NSThread.isMainThread) return null
    val sem = dispatch_semaphore_create(0)
    var out: T? = null
    var finished = false
    dispatch_async(dispatch_get_main_queue()) {
        block { v ->
            // A delegate can be told twice (a pick and then a dismissal); only the first answer counts.
            if (!finished) { finished = true; out = v; dispatch_semaphore_signal(sem) }
        }
    }
    dispatch_semaphore_wait(sem, DISPATCH_TIME_FOREVER)
    return out
}

/** UIKit holds a picker's delegate weakly, so the one in use is kept here until the picker closes. */
private var livePicker: PickerDelegate? = null

private class PickerDelegate(private val done: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        finish(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = finish(null)

    fun cancelled() = finish(null)

    private fun finish(url: NSURL?) {
        livePicker = null
        done(url)
    }
}

/** Shows [picker] and waits for the document the user chose; null when cancelled or with nothing to present from. */
private fun present(picker: () -> UIDocumentPickerViewController): NSURL? = awaitOnMain<NSURL?> { done ->
    val from = topController() ?: return@awaitOnMain done(null)
    val p = picker()
    val delegate = PickerDelegate(done)
    livePicker = delegate
    p.delegate = delegate
    // UIKit refuses a presentation without calling the delegate (the controller is mid-dismissal, say); waiting would never end.
    // UIKit sets this at once for a presentation it accepts.
    from.presentViewController(p, animated = true, completion = null)
    if (p.presentingViewController == null) delegate.cancelled()
}

/**
 * Where each staged path's document really lives: the file the user picked, which [publishFile] writes back to. Read on
 * the UI thread and written from pickers on others, so every use holds [sourcesLock].
 */
private val stagedSources = mutableMapOf<String, NSURL>()
private val sourcesLock = NSLock()

private fun <T> withSources(block: MutableMap<String, NSURL>.() -> T): T {
    sourcesLock.lock()
    try {
        return stagedSources.block()
    } finally {
        sourcesLock.unlock()
    }
}

/** Creates [path] and the folders above it when missing; whether it is there now. */
internal fun ensureDir(path: String): Boolean =
    NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)

/** File names from a picker are the user's; keep only what is safe as a single path segment. */
private fun safeName(name: String?, fallback: String) =
    name.orEmpty().substringAfterLast('/').replace(Regex("[^\\w. -]"), "_").trim().ifEmpty { fallback }

/** A new, empty folder of [kind] under the app's temporary directory, so two documents of one name never share a file. */
private fun stagingDir(kind: String): String? {
    val dir = NSTemporaryDirectory() + "$kind/" + NSUUID().UUIDString
    return dir.takeIf { ensureDir(it) }
}

/** Reads or writes [url] inside its security scope (a document outside the app's sandbox needs one). */
internal fun <T> scoped(url: NSURL, block: () -> T): T {
    val opened = url.startAccessingSecurityScopedResource()
    try {
        return block()
    } finally {
        if (opened) url.stopAccessingSecurityScopedResource()
    }
}

/**
 * The engine reads and writes paths; the document picker hands out URLs that may lie outside the app. So Open copies the
 * chosen document into the app's temporary directory and returns that path, and Save As has the user choose a place
 * first (a placeholder is moved there), then returns a path that [publishFile] copies to that place once the engine has
 * written it. Blocks until the picker closes; call off the main thread.
 */
actual fun pickFile(save: Boolean, suggestedName: String): String? = if (save) pickSave(suggestedName) else pickOpen()

private fun pickOpen(): String? {
    val url = present { UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = false) } ?: return null
    return stage(url)
}

/** A fresh copy of the document at [url] under the app's temporary directory, remembered as its source; null when unreadable. */
internal fun stage(url: NSURL): String? {
    val dir = stagingDir("open") ?: return null
    val path = "$dir/" + safeName(url.lastPathComponent, "document.docx")
    val data = scoped(url) { NSData.dataWithContentsOfURL(url) } ?: return null
    if (!data.writeToFile(path, atomically = true)) return null
    withSources { this[path] = url }
    return path
}

private fun pickSave(suggestedName: String): String? {
    val name = safeName(suggestedName, "Untitled.docx")
    val dir = stagingDir("save") ?: return null
    // The export picker moves an existing file, so it is given an empty one of the right name to place.
    val placeholder = "$dir/placeholder/$name"
    val fm = NSFileManager.defaultManager
    if (!ensureDir("$dir/placeholder")) return null
    if (!fm.createFileAtPath(placeholder, contents = null, attributes = null)) return null
    val dest = present { UIDocumentPickerViewController(forExportingURLs = listOf(NSURL.fileURLWithPath(placeholder)), asCopy = false) }
        ?: return null
    val path = "$dir/" + safeName(dest.lastPathComponent, name)
    withSources { this[path] = dest }
    return path
}

/** Copies a document the engine wrote to a staged path over to the file the user picked. Failure leaves Save As open to them. */
actual fun publishFile(path: String) {
    val dest = withSources { this[path] } ?: return
    val data = NSData.dataWithContentsOfFile(path) ?: return
    // Atomic writes need a temporary file beside the destination, which a document provider may refuse.
    scoped(dest) { data.writeToURL(dest, atomically = false) }
}

/** The document the staged [path] came from, or null when the path is the document. */
internal fun pickedSource(path: String): NSURL? = withSources { this[path] }

/** A new file per job: the print sheet reads it after printPdf returns, so a later job must not overwrite it. */
actual fun printJobPath(): String = (stagingDir("print") ?: NSTemporaryDirectory().trimEnd('/')) + "/job.pdf"

actual fun printPdf(path: String, jobName: String): String? {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return "The document was not exported for printing"
    val url = NSURL.fileURLWithPath(path)
    if (!UIPrintInteractionController.canPrintURL(url)) return "This device cannot print the document"
    dispatch_async(dispatch_get_main_queue()) {
        val controller = UIPrintInteractionController.sharedPrintController
        controller.printInfo = UIPrintInfo.printInfo().apply {
            this.jobName = jobName
            outputType = UIPrintInfoOutputType.UIPrintInfoOutputGeneral
        }
        controller.printingItem = url
        controller.presentAnimated(true, completionHandler = null)
    }
    return null
}

/** Runs [block] on the main thread (UIKit's pasteboard expects it, and asks there for permission to paste). */
private fun <T> onMain(block: () -> T): T? = if (NSThread.isMainThread) block() else awaitOnMain<T> { done -> done(block()) }

actual fun clipboardText(): String? = onMain { UIPasteboard.generalPasteboard.string }?.ifEmpty { null }

actual fun setClipboardText(text: String) {
    onMain { UIPasteboard.generalPasteboard.string = text }
}

actual fun previewFontFamily(identity: String, bytes: ByteArray): androidx.compose.ui.text.font.FontFamily? =
    runCatching { androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.platform.Font(identity, bytes)) }.getOrNull()
