package app.sable.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.sable.data.FavoriteEntity
import app.sable.data.SableDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Favourites: songs marked with the heart, newest first. Kept on the phone; they never change how a song sounds. */
object Favorites {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun set(context: Context, trackId: Long, on: Boolean) {
        val dao = SableDatabase.get(context).favorites()
        io.launch { if (on) dao.add(FavoriteEntity(trackId, System.currentTimeMillis())) else dao.remove(trackId) }
    }
}

/** The favourite track ids, newest first (empty until the database answers). */
@Composable
fun rememberFavorites(): List<Long> {
    val list by LibraryData.favorites.collectAsState()
    return remember(list) { list.map { it.trackId } }
}
