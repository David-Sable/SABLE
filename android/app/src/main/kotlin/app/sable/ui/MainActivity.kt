package app.sable.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import app.sable.data.SableDatabase
import app.sable.data.TrackWithAnalysis
import app.sable.library.JitAnalyzer
import app.sable.library.LibraryScanner
import app.sable.library.TrackAnalyzer
import app.sable.work.ScanWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Milestone 4 debug screen: grant access, scan, see per-track analysis. The real UI arrives in M6.
 * adb automation (results logged under tag "SableBench"):
 *   adb shell am start -n app.sable/.ui.MainActivity --es cmd sync
 *   adb shell am start -n app.sable/.ui.MainActivity --es cmd bench --es match "<title substring>"
 */
class MainActivity : ComponentActivity() {
    private val ink = Color(0xFFE8E1D3)  // warm ivory accent (PLAN decision)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needed = listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) askPermissions.launch(needed.toTypedArray())
        handleCommand(intent)
        setContent { Screen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommand(intent)
    }

    private fun handleCommand(intent: Intent?) {
        val cmd = intent?.getStringExtra("cmd") ?: return
        lifecycleScope.launch(Dispatchers.Default) {
            when (cmd) {
                "sync" -> Log.i(TAG, "sync: ${LibraryScanner(applicationContext).syncMediaStore()} tracks")
                "scan" -> ScanWorker.enqueue(applicationContext, requireCharging = false)
                "bench" -> bench(intent.getStringExtra("match") ?: "")
            }
        }
    }

    /** Full analysis + JIT on matching tracks, logging decode/engine/JIT timings. */
    private suspend fun bench(match: String) {
        LibraryScanner(applicationContext).syncMediaStore()
        val dao = SableDatabase.get(applicationContext).library()
        val tracks = dao.allTracks().filter { it.title.contains(match, ignoreCase = true) || it.displayName.contains(match, ignoreCase = true) }
        for (t in tracks) {
            val a = TrackAnalyzer(applicationContext).analyze(Uri.parse(t.uri), "bench")
            val j = JitAnalyzer(applicationContext).analyze(Uri.parse(t.uri), t.durationMs)
            Log.i(TAG, "BENCH ${t.displayName} | ${t.mime} | dur ${t.durationMs / 1000.0}s | decode ${a.decodeMs} ms | engine ${a.engineMs} ms | " +
                "clipped ${a.decodeClipped} | I ${a.integratedLufs} | gainStd ${a.gainStandardDb} | ${a.character} | JIT ${j.millis} ms ${j.json}")
            Log.i(TAG, "JSON ${t.displayName} ${a.json.take(600)}")
        }
        Log.i(TAG, "BENCH done (${tracks.size} tracks)")
    }

    @Composable
    private fun Screen() {
        val dao = remember { SableDatabase.get(applicationContext).library() }
        val lib by dao.observeLibrary(SableDatabase.MATH_VERSION).collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
            Spacer(Modifier.height(32.dp))
            Text("SABLE · M4 library", color = ink, fontSize = 22.sp)
            Text("${lib.size} tracks · ${lib.count { it.gainStandardDb != null }} analysed $status", color = Color(0xFF9A948A), fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                Button(onClick = { scope.launch(Dispatchers.Default) { status = "· found ${LibraryScanner(applicationContext).syncMediaStore()}" } }) { Text("Find music") }
                Button(onClick = { ScanWorker.enqueue(applicationContext, requireCharging = false); status = "· scanning" }) { Text("Analyse now") }
                Button(onClick = { ScanWorker.enqueue(applicationContext, requireCharging = true); status = "· when charging" }) { Text("While charging") }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(lib, key = { it.mediaId }) { t -> Row(t) { scope.launch { jit(t) } } }
            }
        }
    }

    private suspend fun jit(t: TrackWithAnalysis) = withContext(Dispatchers.Default) {
        val track = SableDatabase.get(applicationContext).library().track(t.mediaId) ?: return@withContext
        val r = JitAnalyzer(applicationContext).analyze(Uri.parse(track.uri), track.durationMs)
        Log.i(TAG, "JIT ${track.displayName}: ${r.millis} ms ${r.json}")
    }

    @Composable
    private fun Row(t: TrackWithAnalysis, onClick: () -> Unit) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
            Text(t.title.ifEmpty { "(untitled)" }, color = ink, fontSize = 15.sp)
            val detail = if (t.gainStandardDb == null) "${t.artist} · not analysed" else
                "${t.artist} · ${t.character} · I %.1f LUFS · gain %+.1f dB (quiet %+.1f) · %d+%d ms"
                    .format(t.integratedLufs ?: Double.NaN, t.gainStandardDb, t.gainQuietDb ?: Double.NaN, t.decodeMs ?: 0, t.engineMs ?: 0)
            Text(detail, color = Color(0xFF8A847A), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }

    companion object {
        const val TAG = "SableBench"
    }
}
