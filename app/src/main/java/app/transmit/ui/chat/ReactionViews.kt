package app.transmit.ui.chat

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.transmit.R
import app.transmit.data.ContactInfo
import app.transmit.messaging.Reaction
import app.transmit.ui.components.Avatar
import coil3.compose.AsyncImage
import java.util.concurrent.ConcurrentHashMap

/**
 * The reactions under a bubble. Tapping one shows who reacted ([WhoReactedSheet]), as in Google Messages; reacting
 * and changing a reaction are in the long-press sheet.
 */
@Composable
fun ReactionChips(reactions: List<Reaction>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val seeWho = stringResource(R.string.reactions_see_who)
    val photomoji = stringResource(R.string.reactions_photomoji)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        reactions.forEach { r ->
            // A plain Box: a clickable Surface would grow to the 48dp touch minimum.
            Box(
                Modifier
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .clip(CircleShape)
                    .background(if (r.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(onClickLabel = seeWho, onClick = onClick)
                    .semantics { contentDescription = describe(r, photomoji) }
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
                    ReactionGlyph(r, size = 17.dp)
                    if (r.count > 1) Text(" ${r.count}", fontSize = 13.sp)
                }
            }
        }
    }
}

/** Who reacted to [item], the user first. The user's own reaction can be removed from here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhoReactedSheet(item: MessageUi, people: Map<String, ContactInfo>, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.reactions_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            item.reactions.filter { it.mine }.forEach { r ->
                // Only a reaction Google Messages tracks can be taken back; a tapback text it didn't recognize can't.
                val removable = item.message.reactions.any { it.mine && it.emoji == r.emoji }
                ReactorRow(
                    r, stringResource(R.string.reactions_you), if (removable) stringResource(R.string.reactions_tap_to_remove) else null,
                    avatar = { PlaceholderAvatar(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) },
                    onClick = if (removable) ({ onRemove(r.emoji) }) else null,
                )
            }
            item.reactions.forEach { r ->
                r.others.forEach { address ->
                    val person = people[address] ?: ContactInfo(address, null, null, null)
                    ReactorRow(r, person.displayName, null, avatar = { Avatar(person.displayName, person.address, person.photoUri, size = AvatarSize) })
                }
                // People the phone counts but doesn't list in the chat (someone who left the group).
                repeat((r.count - r.others.size - if (r.mine) 1 else 0).coerceAtLeast(0)) {
                    ReactorRow(r, stringResource(R.string.reactions_someone), null, avatar = { PlaceholderAvatar(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) })
                }
            }
        }
    }
}

private val AvatarSize = 40.dp

/** A person without a picture or a known name: the user, or someone the chat doesn't list. */
@Composable
private fun PlaceholderAvatar(background: Color, tint: Color) {
    Box(Modifier.size(AvatarSize).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Person, null, Modifier.size(AvatarSize * 0.6f), tint = tint)
    }
}

@Composable
private fun ReactorRow(r: Reaction, name: String, note: String?, avatar: @Composable () -> Unit, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(8.dp))
        val photomoji = stringResource(R.string.reactions_photomoji)
        Box(Modifier.semantics { contentDescription = describe(r, photomoji, count = false) }) { ReactionGlyph(r, size = 28.dp) }
    }
}

/**
 * A reaction as it's drawn: its emoji, or a photomoji's picture. A photomoji whose picture can't be had shows a picture
 * icon, never its id.
 */
@Composable
fun ReactionGlyph(r: Reaction, size: Dp) {
    val photomoji = r.photomoji
    if (photomoji == null) {
        Text(r.emoji, fontSize = (size.value * 0.78f).sp)
        return
    }
    var failed by remember(photomoji.image) { mutableStateOf(false) }
    if (photomoji.image != null && !failed) {
        AsyncImage(
            model = photomoji.image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size),
            onError = { state ->
                failed = true
                // The error's type and message only: a stack trace could carry the picture's address.
                val e = state.result.throwable
                if (failedPhotomoji.add(photomoji.image)) Log.w(TAG, "A photomoji's picture didn't load: ${e.javaClass.simpleName}: ${e.message}")
            },
        )
    } else {
        Icon(Icons.Outlined.Image, null, Modifier.size(size), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What TalkBack reads for [r]: its emoji, or [photomoji] for a picture, and how many reacted with it. */
private fun describe(r: Reaction, photomoji: String, count: Boolean = true): String {
    val what = if (r.photomoji != null) photomoji else r.emoji
    return if (count && r.count > 1) "$what, ${r.count}" else what
}

private const val TAG = "Reactions"

/** Pictures already logged as failing, so a list scrolling past them logs each once. */
private val failedPhotomoji: MutableSet<String> = ConcurrentHashMap.newKeySet()
