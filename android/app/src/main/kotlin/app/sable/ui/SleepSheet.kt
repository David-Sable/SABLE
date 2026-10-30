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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sable.playback.SleepTimer
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space
import android.os.SystemClock
import java.text.DateFormat
import java.util.Date

/** One line for the timer's state, or null when it is off. */
fun sleepStatus(s: SleepTimer.State?): String? = when {
    s == null -> null
    s.deadlineMs == null -> "Stops after this song"
    else -> {
        val wall = System.currentTimeMillis() + (s.deadlineMs - SystemClock.elapsedRealtime())
        "Stops at the end of the song playing at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(wall))}"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepSheet(onDismiss: () -> Unit) {
    val st by SleepTimer.state.collectAsState()
    SableSheet(onDismiss) {
        val close = LocalSheetClose.current
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl).navigationBarsPadding()) {
            Text("SLEEP TIMER", style = SableType.overline, color = SableColors.textTertiary, modifier = Modifier.padding(top = Space.m))
            Text(sleepStatus(st) ?: "Off", style = SableType.title, color = if (st != null) SableColors.accent else SableColors.textPrimary,
                modifier = Modifier.padding(top = Space.xs))
            Text("Sable always lets the song finish. It never fades or cuts the music.", style = SableType.caption, color = SableColors.textTertiary,
                modifier = Modifier.padding(top = Space.xs))
            Spacer(Modifier.height(Space.l))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Pill("End of this song", selected = st != null && st?.minutes == 0) { SleepTimer.endOfSong(); close() }
                for (m in SleepTimer.choices) {
                    val label = if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "${m / 60} h ${m % 60}"
                    Pill(label, selected = st?.minutes == m) { SleepTimer.after(m); close() }
                }
                if (st != null) Pill("Turn off", selected = false) { SleepTimer.cancel(); close() }
            }
            Spacer(Modifier.height(Space.xl))
        }
    }
}
