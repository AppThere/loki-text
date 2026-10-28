package app.loki.text

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import app.loki.text.engine.Engine
import app.loki.text.engine.EngineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Turns straight-alpha RGBA into a Compose image; per platform (Skia). */
expect fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap

/** Toolbar buttons that run a WordCraft command by id with no parameters. */
private val COMMAND_BUTTONS = listOf(
    "Bold" to "format.bold",
    "Italic" to "format.italic",
    "Underline" to "format.underline",
    "Undo" to "edit.undo",
    "Redo" to "edit.redo",
)

/**
 * Walking skeleton: a text box feeds `text.insert`, Bold runs `format.bold`, and the page the Rust
 * engine laid out and rasterised is shown below. The real UI (ribbon, pane, caret, selection)
 * builds on the same three calls: execute, inspect, renderPage.
 */
@Composable
fun App() = MaterialTheme {
    val engine = remember { Engine() }
    DisposableEffect(Unit) { onDispose { engine.close() } }

    var input by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf("") }

    // Engine calls block, so they run off the UI thread; one at a time keeps edits ordered.
    suspend fun perform(block: Engine.() -> Unit) {
        status = try {
            withContext(Dispatchers.Default) { engine.block() }
            revision++
            ""
        } catch (e: EngineException) {
            e.message ?: "error"
        }
    }

    LaunchedEffect(revision) {
        page = withContext(Dispatchers.Default) {
            engine.renderPage(0, 1.5f)?.let { rgbaToImageBitmap(it.width, it.height, it.rgba) }
        }
    }

    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row {
            OutlinedTextField(input, { input = it }, label = { Text("Text to insert") })
            Button(onClick = {
                val t = input
                input = ""
                scope.launch { perform { insertText(t) } }
            }, modifier = Modifier.padding(start = 8.dp)) { Text("Insert") }
            for ((label, command) in COMMAND_BUTTONS) {
                Button(onClick = { scope.launch { perform { execute(command) } } }, modifier = Modifier.padding(start = 8.dp)) { Text(label) }
            }
        }
        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.error)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            page?.let { Image(it, contentDescription = "Page 1") }
        }
    }
}
