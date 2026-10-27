package app.sable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sable.data.LibraryRow
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space

/** Long-press on a song: slip it in after the playing song or at the end of the queue, without stopping the music. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SongMenu(state: AppState, r: LibraryRow, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = SableColors.surface1, scrimColor = SableColors.base.copy(alpha = 0.6f),
        dragHandle = { Box(Modifier.padding(top = Space.m).size(36.dp, 4.dp).background(SableColors.line, RoundedCornerShape(2.dp))) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl).navigationBarsPadding()) {
            Text(titleOf(r), style = SableType.title, color = SableColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Space.m))
            Text(listOf(clean(r.artist), clean(r.album)).filter { it.isNotBlank() }.joinToString(" · "), style = SableType.body,
                color = SableColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.l))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Pill("Play next", selected = true) { state.playNext(r); onDismiss() }
                Pill("Add to queue", selected = false) { state.addToQueue(r); onDismiss() }
                Pill("Go to album", selected = false) { onDismiss(); state.go(Route.Album(albumKey(r))) }
                Pill("Why", selected = false) { onDismiss(); state.whyTrack = r.mediaId }
            }
            Text("Added songs play in their own world, balanced on their own.", style = SableType.caption, color = SableColors.textTertiary,
                modifier = Modifier.padding(top = Space.m))
            Spacer(Modifier.height(Space.xl))
        }
    }
}
