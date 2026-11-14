package app.loki.text

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One comment or reply from `review.comments`. [parent] is null for a comment that starts a thread. */
internal class CommentCard(
    val id: Int,
    val parent: Int?,
    val author: String,
    val date: String,
    val resolved: Boolean,
    val text: String,
    /** A `Pos` to hand to `caret.set`, or null when the comment isn't anchored (a reply). */
    val anchor: JsonObject?,
)

/** A comment and the replies to it, oldest first. */
internal class CommentThread(val root: CommentCard, val replies: List<CommentCard>)

private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

internal fun parseComments(list: JsonElement?): List<CommentCard> = (list as? JsonArray).orEmpty().mapNotNull { e ->
    val o = e as? JsonObject ?: return@mapNotNull null
    CommentCard(
        id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
        parent = (o["parent"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
        author = o["author"].str(),
        date = o["date"].str(),
        resolved = (o["resolved"] as? JsonPrimitive)?.booleanOrNull == true,
        text = o["text"].str(),
        anchor = o["anchor"] as? JsonObject,
    )
}

/**
 * Comments grouped into threads in document order. A reply to a reply joins the thread of the
 * comment at its root; a reply whose parent is missing becomes a thread of its own.
 */
internal fun commentThreads(cards: List<CommentCard>): List<CommentThread> {
    val byId = cards.associateBy { it.id }
    fun rootOf(c: CommentCard): CommentCard {
        var cur = c
        var guard = 0
        // A cycle in malformed data must not loop forever.
        while (guard++ < 1000) cur = cur.parent?.let { byId[it] } ?: return cur
        return cur
    }
    val groups = LinkedHashMap<Int, MutableList<CommentCard>>()
    for (c in cards) {
        val root = rootOf(c)
        if (root.id == c.id) groups.getOrPut(c.id) { mutableListOf() } else groups.getOrPut(root.id) { mutableListOf() }.add(c)
    }
    return groups.mapNotNull { (id, replies) -> byId[id]?.let { CommentThread(it, replies) } }
}

/** `2026-11-08T14:30:07Z` as `2026-11-08 14:30 UTC`; anything else as given. */
internal fun commentDate(iso: String): String {
    val m = Regex("""^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})""").find(iso) ?: return iso
    return "${m.groupValues[1]} ${m.groupValues[2]}" + if (iso.endsWith("Z")) " UTC" else ""
}

/**
 * The Comments pane: every comment with its replies, with Go to, Reply, Edit, Resolve and Delete,
 * and a New Comment box. [run] calls an engine command. [revision] changes with every edit.
 */
@Composable
internal fun CommentsPane(
    revision: Int,
    run: suspend (String, JsonObject) -> Result<JsonElement?>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var threads by remember { mutableStateOf<List<CommentThread>>(emptyList()) }
    var showResolved by remember { mutableStateOf(true) }
    var newText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }

    suspend fun reload() {
        run("review.comments", JsonObject(emptyMap())).onSuccess { threads = commentThreads(parseComments(it)) }
    }
    LaunchedEffect(revision) { delay(150); reload() }

    // Runs a command, shows why it failed, and refreshes the list either way.
    fun act(cmd: String, params: JsonObject, then: () -> Unit = {}) = scope.launch {
        run(cmd, params).fold({ error = ""; then() }, { error = it.message ?: "Failed" })
        reload()
    }

    Surface(tonalElevation = 2.dp, modifier = modifier) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Comments", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                FilterChip(showResolved, { showResolved = !showResolved }, label = { Text("Resolved") })
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Lucide.X, contentDescription = "Close comments pane", modifier = Modifier.size(18.dp)) }
            }
            val draft = newText
            if (draft == null) {
                Button(onClick = { newText = "" }) { Text("New Comment") }
            } else {
                OutlinedTextField(draft, { newText = it }, label = { Text("Comment on the selection") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(enabled = draft.isNotBlank(), onClick = { act("review.newComment", buildJsonObject { put("text", draft.trim()) }) { newText = null } }) { Text("Post") }
                    TextButton(onClick = { newText = null }) { Text("Cancel") }
                }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            val shown = threads.filter { showResolved || !it.root.resolved }
            if (shown.isEmpty()) {
                Text(if (threads.isEmpty()) "No comments yet. Select some text and choose New Comment." else "All comments are resolved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.root.id }) { t ->
                    ThreadCard(t, ::act)
                }
            }
        }
    }
}

@Composable
private fun ThreadCard(t: CommentThread, act: (String, JsonObject, () -> Unit) -> Unit) {
    var replying by remember { mutableStateOf<String?>(null) }
    Surface(shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CommentBody(t.root, act, goTo = true)
            for (r in t.replies) {
                Column(Modifier.padding(start = 12.dp)) { CommentBody(r, act, goTo = false) }
            }
            val draft = replying
            if (draft != null) {
                OutlinedTextField(draft, { replying = it }, label = { Text("Reply") }, modifier = Modifier.fillMaxWidth(), minLines = 1)
                Row {
                    TextButton(enabled = draft.isNotBlank(), onClick = { act("review.reply", buildJsonObject { put("id", t.root.id); put("text", draft.trim()) }) { replying = null } }) { Text("Post") }
                    TextButton(onClick = { replying = null }) { Text("Cancel") }
                }
            } else {
                TextButton(onClick = { replying = "" }) { Text("Reply") }
            }
        }
    }
}

@Composable
private fun CommentBody(c: CommentCard, act: (String, JsonObject, () -> Unit) -> Unit, goTo: Boolean) {
    var editing by remember(c.id) { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(c.author, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(commentDate(c.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        if (c.resolved) Text("Resolved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
    }
    val draft = editing
    if (draft != null) {
        OutlinedTextField(draft, { editing = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row {
            TextButton(onClick = { act("review.editComment", buildJsonObject { put("id", c.id); put("text", draft) }) { editing = null } }) { Text("Save") }
            TextButton(onClick = { editing = null }) { Text("Cancel") }
        }
    } else {
        Text(c.text, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            if (goTo && c.anchor != null) TextButton(onClick = { act("caret.set", buildJsonObject { put("pos", c.anchor) }) {} }) { Text("Go to") }
            TextButton(onClick = { editing = c.text }) { Text("Edit") }
            if (goTo) TextButton(onClick = { act("review.resolveComment", buildJsonObject { put("id", c.id) }) {} }) { Text(if (c.resolved) "Reopen" else "Resolve") }
            TextButton(onClick = { act("review.deleteComment", buildJsonObject { put("id", c.id) }) {} }) { Text("Delete") }
        }
    }
}
