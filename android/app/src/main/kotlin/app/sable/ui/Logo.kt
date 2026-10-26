package app.sable.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sable.R

/**
 * The SABLE mark: the white lion (drawable/lion_mark.xml, the same vector as the launcher icon), [height] tall.
 * Used sparingly: beside the title, in About, and on the empty Now Playing screen.
 */
@Composable
fun SableMark(height: Dp = 28.dp, modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.lion_mark), contentDescription = "SABLE",
        modifier = modifier.height(height).aspectRatio(1784f / 1428f),
    )
}
