package app.transmit.ui.components

import android.net.Uri
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.transmit.data.Language
import app.transmit.notify.Avatars
import coil3.compose.AsyncImage
import java.util.Calendar

@Composable
fun Avatar(name: String, seed: String, photo: Uri?, size: Dp = 40.dp, group: Boolean = false, modifier: Modifier = Modifier) {
    val color = Color(Avatars.colorFor(seed))
    // A photo that won't load (a group picture offline, a deleted file) falls back to the drawn avatar.
    var failed by remember(photo) { mutableStateOf(false) }
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        when {
            photo != null && !failed -> AsyncImage(
                model = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size),
                onError = { failed = true },
            )
            group -> Icon(Icons.Default.Group, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
            else -> Text(
                name.firstOrNull { it.isLetter() }?.uppercase() ?: "#",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

/** Compact flag + code chip identifying a language without words. */
@Composable
fun LanguageBadge(language: Language, modifier: Modifier = Modifier, showCode: Boolean = true) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            if (showCode) "${language.flag} ${language.badge}" else language.flag,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

object Times {
    fun short(context: android.content.Context, time: Long, now: Long = System.currentTimeMillis()): String {
        val then = Calendar.getInstance().apply { timeInMillis = time }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        return when {
            now - time < DateUtils.MINUTE_IN_MILLIS -> "now"
            sameDay(then, today) -> DateFormat.getTimeFormat(context).format(time)
            now - time < 6 * DateUtils.DAY_IN_MILLIS -> DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
            then.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
            else -> DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE)
        }
    }

    fun time(context: android.content.Context, time: Long): String = DateFormat.getTimeFormat(context).format(time)

    fun day(context: android.content.Context, time: Long, now: Long = System.currentTimeMillis()): String {
        val then = Calendar.getInstance().apply { timeInMillis = time }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val yesterday = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            sameDay(then, today) -> "Today"
            sameDay(then, yesterday) -> "Yesterday"
            time < now && now - time < 6 * DateUtils.DAY_IN_MILLIS -> DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_WEEKDAY)
            else -> DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
        }
    }

    fun full(context: android.content.Context, time: Long): String =
        DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_WEEKDAY)

    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

@Composable
fun rememberContext() = LocalContext.current
