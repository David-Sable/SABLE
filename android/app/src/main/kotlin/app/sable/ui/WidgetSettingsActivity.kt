package app.sable.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sable.R
import app.sable.playback.SableWidget
import app.sable.playback.WidgetLook
import app.sable.ui.theme.SableColors
import app.sable.ui.theme.SableTheme
import app.sable.ui.theme.SableType
import app.sable.ui.theme.Space

/**
 * The widget's settings (long-press the widget, Settings; also shown once when it is placed, and skippable). The window is
 * see-through to the wallpaper, so the live preview sits on the real home screen; every change reaches the placed widget at once.
 */
class WidgetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        // Placing the widget never depends on this screen: leaving it any way keeps the widget.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        setContent {
            SableTheme {
                var look by remember { mutableStateOf(WidgetLook.get(this, id)) }
                fun change(l: WidgetLook.Look) { look = l; WidgetLook.set(this, id, l); SableWidget.refresh(this) }
                WidgetSettings(look, ::change) { finish() }
            }
        }
    }
}

@Composable
private fun WidgetSettings(look: WidgetLook.Look, change: (WidgetLook.Look) -> Unit, done: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // The wallpaper shows here, with the widget as it will look on it.
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.l), contentAlignment = Alignment.Center) { Preview(look) }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(SableColors.surface1)
                .navigationBarsPadding().padding(horizontal = Space.xl, vertical = Space.l),
        ) {
            Text("WIDGET", style = SableType.overline, color = SableColors.textTertiary)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.l)) {
                Text("Background", style = SableType.body, color = SableColors.textPrimary, modifier = Modifier.weight(1f))
                Text(if (look.opacity == 0) "Clear" else if (look.opacity == 100) "Solid" else "${look.opacity} %", style = SableType.caption, color = SableColors.textSecondary)
            }
            Slider(
                value = look.opacity.toFloat(), valueRange = 0f..100f, steps = 9,
                onValueChange = { change(look.copy(opacity = (Math.round(it / 10f) * 10))) },
                colors = SliderDefaults.colors(thumbColor = SableColors.accent, activeTrackColor = SableColors.accent, inactiveTrackColor = SableColors.line),
            )
            Choice("Card", listOf("Sable black" to !look.glow, "Cover glow" to look.glow)) { change(look.copy(glow = it == 1)) }
            Choice("Album name", listOf("Show" to look.album, "Hide" to !look.album)) { change(look.copy(album = it == 0)) }
            Row(Modifier.fillMaxWidth().padding(top = Space.xl), horizontalArrangement = Arrangement.End) { Pill("Done", selected = true, onClick = done) }
        }
    }
}

@Composable
private fun Choice(label: String, options: List<Pair<String, Boolean>>, pick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Space.l), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = SableType.body, color = SableColors.textPrimary, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { options.forEachIndexed { i, (text, on) -> Pill(text, on) { pick(i) } } }
    }
}

/** The widget at its main size, drawn as the home screen will draw it (SableWidget's large layout and WidgetLook). */
@Composable
private fun Preview(look: WidgetLook.Look) {
    val s = SableWidget.lastState()
    val art = s?.art
    val glow = remember(art) { art?.let { WidgetLook.glowOf(it).asImageBitmap() } }
    val a = look.opacity / 100f
    // The widget's own type: serif title 19sp, artist 13sp, album 12sp, each with the soft shadow that keeps it readable on a wallpaper.
    val shadow = Shadow(Color(0x73000000), androidx.compose.ui.geometry.Offset(0f, 2f), 12f)
    val titleStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontSize = 19.sp, lineHeight = 23.sp, shadow = shadow)
    val small = { size: Int -> androidx.compose.ui.text.TextStyle(fontSize = size.sp, shadow = shadow) }
    Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(24.dp))) {
        if (look.glow && glow != null) Image(glow, null, Modifier.fillMaxSize().alpha(a), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().background(Color(0xFF0A0A09).copy(alpha = a)))
        Row(Modifier.fillMaxSize().padding(14.dp)) {
            val cover = Modifier.size(142.dp).clip(RoundedCornerShape(10.dp))
            if (art != null) Image(art.asImageBitmap(), null, cover, contentScale = ContentScale.Crop)
            else Box(cover.background(Color(0xFF121110)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.widget_lion), null, Modifier.size(64.dp), colorFilter = ColorFilter.tint(SableColors.accent))
            }
            Column(Modifier.weight(1f).height(142.dp).padding(start = 16.dp)) {
                Text(s?.title ?: "Sable", style = titleStyle, color = Color(0xFFEDE8DF), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(s?.artist ?: "Tap play to resume", style = small(13), color = Color(0xFFA39D92), maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                if (look.album && !s?.album.isNullOrBlank()) Text(s!!.album, style = small(12), color = Color(0xFF7A756C), maxLines = 1)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_w_prev), null, Modifier.size(44.dp).padding(10.dp))
                    Box(Modifier.padding(horizontal = 6.dp).size(44.dp).clip(CircleShape).background(Color(0xFFB5975A)), contentAlignment = Alignment.Center) {
                        Image(painterResource(if (s?.playing == true) R.drawable.ic_w_pause else R.drawable.ic_w_play), null, Modifier.size(24.dp))
                    }
                    Image(painterResource(R.drawable.ic_w_next), null, Modifier.size(44.dp).padding(10.dp))
                    Spacer(Modifier.width(1.dp))
                }
            }
        }
    }
}
