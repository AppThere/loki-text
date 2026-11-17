package app.loki.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.concurrent.CompletableFuture
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap {
    val px = IntArray(width * height) { i ->
        val o = i * 4
        val r = rgba[o].toInt() and 0xFF
        val g = rgba[o + 1].toInt() and 0xFF
        val b = rgba[o + 2].toInt() and 0xFF
        val a = rgba[o + 3].toInt() and 0xFF
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** The running activity, which hosts the file pickers; null before onCreate and after onDestroy. */
private var activity: MainActivity? = null

/** A saved document waits here, in app storage, until [publishFile] copies it to the place the user chose. */
private val pendingSaves = HashMap<String, Uri>()

private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/** The media type of a file to create, from its extension; a document when it has none this app writes. */
private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "dotx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.template"
    "odt" -> "application/vnd.oasis.opendocument.text"
    "rtf" -> "application/rtf"
    "txt" -> "text/plain"
    "md" -> "text/markdown"
    "html", "htm" -> "text/html"
    else -> DOCX
}

private fun ContentResolver.displayName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

/** File names from a picker are the user's; keep only what is safe as a single path segment. */
private fun safeName(name: String?, fallback: String) =
    name.orEmpty().substringAfterLast('/').replace(Regex("[^\\w. -]"), "_").trim().ifEmpty { fallback }

/**
 * The engine reads and writes paths, and the Storage Access Framework hands out content URIs. So
 * Open copies the chosen document into app storage and returns that path; Save As returns a path
 * in app storage and [publishFile] copies it to the chosen place once the engine has written it.
 * Blocks until the picker closes; call off the UI thread.
 */
actual fun pickFile(save: Boolean, suggestedName: String): String? {
    val host = activity ?: return null
    val result = CompletableFuture<Uri?>()
    host.runOnUiThread {
        val registry = host.activityResultRegistry
        val key = "loki-pick-${System.nanoTime()}"
        if (save) {
            // The picker names and types the new file from what is being written (a document, a PDF, a picture).
            val name = safeName(suggestedName, "Untitled.docx")
            registry.register(key, ActivityResultContracts.CreateDocument(mimeFor(name))) { uri: Uri? -> result.complete(uri) }.launch(name)
        } else {
            registry.register(key, ActivityResultContracts.OpenDocument()) { uri: Uri? -> result.complete(uri) }.launch(arrayOf("*/*"))
        }
    }
    val uri = try { result.get() } catch (e: Exception) { null } ?: return null
    val resolver = host.contentResolver
    val dir = File(host.cacheDir, if (save) "save" else "open").apply { mkdirs() }
    var name = safeName(resolver.displayName(uri), "document.docx")
    // A provider may drop the extension; the engine picks the format from it.
    if (save && '.' !in name) name += "." + suggestedName.substringAfterLast('.', "docx").ifEmpty { "docx" }
    val file = File(dir, name)
    return try {
        if (save) {
            pendingSaves[file.path] = uri
        } else {
            resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
            // Save writes back to the document the user opened (when the provider allows writing).
            pendingSaves[file.path] = uri
        }
        file.path
    } catch (e: Exception) {
        null
    }
}

/** Copies a document Save As wrote to app storage over to the file the user picked. */
actual fun publishFile(path: String) {
    val host = activity ?: return
    val uri = pendingSaves[path] ?: return
    // A read-only source (or a revoked grant) must not crash the app; Save As remains.
    runCatching { host.contentResolver.openOutputStream(uri, "wt")?.use { out -> File(path).inputStream().use { it.copyTo(out) } } }
}

/** A new file per job: the print service reads it after we return, so a later job must not overwrite it. */
actual fun printJobPath(): String {
    val dir = File((activity?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")), "print").apply { mkdirs() }
    // Spooled jobs are done with their file long before an hour has passed.
    val stale = System.currentTimeMillis() - 60 * 60 * 1000
    dir.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
    return File(dir, "job-${System.nanoTime()}.pdf").path
}

actual fun printPdf(path: String, jobName: String): String? {
    val host = activity ?: return "No window to print from"
    val file = File(path)
    if (!file.isFile) return "The document was not exported for printing"
    val manager = host.getSystemService(PrintManager::class.java) ?: return "This device has no print service"
    host.runOnUiThread { manager.print(jobName, PdfPrintAdapter(file, jobName), null) }
    return null
}

/** Feeds an already-made PDF to Android's print dialog, which handles printer choice and options. */
private class PdfPrintAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
    override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
        if (cancel?.isCanceled == true) return callback.onLayoutCancelled()
        val info = PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(pages: Array<PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
        try {
            file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}

private fun clipboard(): ClipboardManager? = activity?.getSystemService(ClipboardManager::class.java)

actual fun clipboardText(): String? {
    val clip = clipboard()?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(activity).toString().ifEmpty { null }
}

actual fun setClipboardText(text: String) {
    clipboard()?.setPrimaryClip(ClipData.newPlainText("Loki Text", text))
}

private const val EXTRA_PATH = "app.loki.text.PATH"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activity = this
        // Edge to edge with a pale top bar: dark status-bar icons so the clock stays readable.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        // A document opened from another window arrives as a path in app storage.
        val initial = intent?.getStringExtra(EXTRA_PATH)
        setContent {
            App(initialPath = initial, onNewWindow = { path ->
                // Each document gets its own task, so it has its own entry in Recents.
                val next = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                if (path != null) next.putExtra(EXTRA_PATH, path)
                startActivity(next)
            })
        }
    }

    // Pickers register on the visible window, not the one created last.
    override fun onResume() {
        super.onResume()
        activity = this
    }

    override fun onDestroy() {
        if (activity === this) activity = null
        super.onDestroy()
    }
}

/** The font is written under the app's cache (Android makes typefaces from files), once per name. */
actual fun previewFontFamily(identity: String, bytes: ByteArray): androidx.compose.ui.text.font.FontFamily? = runCatching {
    val dir = File(activity?.cacheDir ?: return null, "preview-fonts").apply { mkdirs() }
    val file = File(dir, identity)
    if (!file.exists() || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
    androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(file))
}.getOrNull()
