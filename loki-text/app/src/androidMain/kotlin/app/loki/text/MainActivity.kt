package app.loki.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.concurrent.CompletableFuture
import android.os.Bundle
import androidx.activity.ComponentActivity
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
actual fun pickFile(save: Boolean): String? {
    val host = activity ?: return null
    val result = CompletableFuture<Uri?>()
    host.runOnUiThread {
        val registry = host.activityResultRegistry
        val key = "loki-pick-${System.nanoTime()}"
        if (save) {
            registry.register(key, ActivityResultContracts.CreateDocument(DOCX)) { uri: Uri? -> result.complete(uri) }.launch("Untitled.docx")
        } else {
            registry.register(key, ActivityResultContracts.OpenDocument()) { uri: Uri? -> result.complete(uri) }.launch(arrayOf("*/*"))
        }
    }
    val uri = try { result.get() } catch (e: Exception) { null } ?: return null
    val resolver = host.contentResolver
    val dir = File(host.cacheDir, if (save) "save" else "open").apply { mkdirs() }
    var name = safeName(resolver.displayName(uri), "document.docx")
    if (save && '.' !in name) name += ".docx"
    val file = File(dir, name)
    return try {
        if (save) {
            pendingSaves[file.path] = uri
        } else {
            resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
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
    host.contentResolver.openOutputStream(uri, "wt")?.use { out -> File(path).inputStream().use { it.copyTo(out) } }
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activity = this
        setContent { App() }
    }

    override fun onDestroy() {
        if (activity === this) activity = null
        super.onDestroy()
    }
}
