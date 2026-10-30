package app.sable.ui

import android.app.Activity
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.sable.data.LibraryRow
import app.sable.library.LibraryScanner
import app.sable.library.Organizer
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top of the Folders tab: offers to tidy the music into Music/Artist/Album (Organizer), says what it will and will not do
 * before anything moves, and keeps an Undo for the last tidy. Android shows its own "allow Sable to modify" once per run.
 */
@Composable
fun OrganizeRow(rows: List<LibraryRow>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plan = remember(rows) { Organizer.plan(rows) }
    var undo by remember { mutableStateOf(Organizer.undoable(context)) }
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var then by remember { mutableStateOf<(() -> Unit)?>(null) }
    val allow = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) then?.invoke() else busy = false
        then = null
    }
    fun ask(moves: List<Organizer.Move>, work: () -> Unit) {
        busy = true; then = work
        val request = MediaStore.createWriteRequest(context.contentResolver, moves.map { it.uri })
        allow.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }
    fun finish(result: Organizer.Result, done: String) {
        scope.launch {
            withContext(Dispatchers.IO) { LibraryScanner(context.applicationContext).syncMediaStore() }
            undo = Organizer.undoable(context)
            note = done + if (result.failed > 0) " ${count(result.failed, "song")} could not be moved and stayed where they were." else ""
            busy = false; open = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (plan.isEmpty()) "Every song is in its album's folder." else "${count(plan.size, "song")} not in album folders",
                style = SableType.caption, color = SableColors.textSecondary, modifier = Modifier.weight(1f),
            )
            if (plan.isNotEmpty() && !open) Pill("Organize", selected = false) { open = true; note = null }
        }
        Reveal(open && plan.isNotEmpty()) {
            Column(Modifier.padding(top = Space.m)) {
                // Sable's own question first (David, 2026-10-30): nothing moves unless the owner says yes here, then Android asks too.
                Text("Is it okay for Sable to organize your files?", style = SableType.body, color = SableColors.textPrimary)
                Text(
                    "Sable moves each song into Music → Artist → Album, named from its tags. The music itself is not changed, and " +
                        "everything Sable knows about it stays: levels, learning, history, favourites and playlists. You can undo it.",
                    style = SableType.caption, color = SableColors.textSecondary, modifier = Modifier.padding(top = Space.xs),
                )
                Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Pill(if (busy) "Moving…" else "Yes, organize ${count(plan.size, "song")}", selected = true) {
                        if (!busy) ask(plan) {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { Organizer.apply(context, plan) }
                                finish(r, "Moved ${count(r.moved, "song")} into album folders.")
                            }
                        }
                    }
                    Pill("Not now", selected = false) { open = false }
                }
            }
        }
        note?.let { Text(it, style = SableType.caption, color = SableColors.accent, modifier = Modifier.padding(top = Space.s)) }
        if (undo.isNotEmpty() && !open) Row(Modifier.padding(top = Space.s)) {
            Pill(if (busy) "Moving…" else "Undo organize", selected = false) {
                if (!busy) ask(undo) {
                    scope.launch {
                        val moves = undo
                        val r = withContext(Dispatchers.IO) { Organizer.undo(context, moves) }
                        finish(r, "Put ${count(r.moved, "song")} back where they were.")
                    }
                }
            }
        }
    }
}
