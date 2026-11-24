package app.loki.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The platform's voice: speaks [text] at [rate] (1 is normal) and calls [onDone] when it has finished (not when it was
 * stopped). [stop] cuts it short. Null where the platform has none this app can use.
 */
interface Speech {
    fun speak(text: String, rate: Float, onDone: () -> Unit)
    fun stop()
}

/** The voice of this platform, or null with nothing to speak through (the Read Aloud bar then says so). */
expect fun platformSpeech(): Speech?

/** What `readAloud.status` reports: the sentence being read (its text and where it is), which of how many, and the state. */
internal data class ReadAloudState(
    val open: Boolean,
    val state: String,
    val index: Int,
    val count: Int,
    val rate: Float,
    val text: String?,
    val start: JsonElement?,
    val end: JsonElement?,
    val error: String?,
) {
    val playing: Boolean get() = open && state == "playing"
    /** The last sentence: when it has been read, reading stops instead of moving on. */
    val last: Boolean get() = count == 0 || index >= count - 1
}

internal fun parseReadAloud(r: JsonElement?): ReadAloudState? {
    val o = r as? JsonObject ?: return null
    fun p(k: String) = o[k] as? JsonPrimitive
    val current = o["current"] as? JsonObject
    return ReadAloudState(
        open = p("open")?.booleanOrNull == true,
        state = p("state")?.contentOrNull ?: "stopped",
        index = p("index")?.intOrNull ?: 0,
        count = p("count")?.intOrNull ?: 0,
        rate = p("rate")?.floatOrNull?.takeIf { it.isFinite() && it > 0f } ?: 1f,
        text = (current?.get("text") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        start = current?.get("start"),
        end = current?.get("end"),
        error = p("error")?.takeIf { it.isString }?.contentOrNull,
    )
}

/** The bar over the page while Read Aloud is on: where it is, and back, play or pause, next, and stop. */
@Composable
internal fun ReadAloudBar(state: ReadAloudState, onRun: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val where = if (state.count > 0) "Reading ${state.index + 1} of ${state.count}" else "Nothing to read"
        Text(
            state.error ?: where,
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = { onRun("readAloud.previous") }, enabled = state.index > 0) { Text("Previous") }
        TextButton(onClick = { onRun("readAloud.playPause") }) { Text(if (state.playing) "Pause" else "Play") }
        TextButton(onClick = { onRun("readAloud.next") }, enabled = !state.last) { Text("Next") }
        Spacer(Modifier.padding(4.dp))
        TextButton(onClick = { onRun("readAloud.stop") }) { Text("Stop") }
    }
}

/** Does [id] start, steer or stop Read Aloud (so its state is read again after it)? */
internal fun isReadAloud(id: String): Boolean = id == "review.readAloud" || id.startsWith("readAloud.")
