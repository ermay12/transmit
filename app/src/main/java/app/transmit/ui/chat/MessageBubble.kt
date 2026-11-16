package app.transmit.ui.chat

import android.text.format.Formatter
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.transmit.data.ContactInfo
import app.transmit.data.Language
import app.transmit.data.db.TranslationOrigin
import app.transmit.notify.Avatars
import app.transmit.smart.SmartEntity
import app.transmit.smart.SmartLinks
import app.transmit.messaging.Attachment
import app.transmit.messaging.DeliveryStatus
import app.transmit.messaging.Reactions
import app.transmit.ui.components.Avatar
import app.transmit.ui.components.Times
import coil3.compose.AsyncImage
import kotlin.math.abs

private val BubbleMax = 280.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    item: MessageUi,
    group: Boolean,
    sender: ContactInfo?,
    target: Language,
    downloading: Boolean,
    smart: SmartLinks,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onReaction: (String) -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onAttachment: (Attachment) -> Unit,
    /** A tapped link, with the bubble's other links (a date's event takes its location from an address). */
    onLink: (SmartEntity, List<SmartEntity>) -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val m = item.message
    val out = m.outgoing
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(top = if (item.firstInRun) 6.dp else 1.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = if (out) Alignment.End else Alignment.Start,
    ) {
        if (group && !out && item.firstInRun && sender != null) {
            Text(
                sender.displayName, style = MaterialTheme.typography.labelSmall, color = Color(Avatars.colorFor(sender.address)),
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 46.dp, bottom = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (group && !out) {
                Box(Modifier.width(38.dp)) {
                    if (item.lastInRun && sender != null) Avatar(sender.displayName, sender.address, sender.photoUri, size = 30.dp)
                }
            }
            Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
                Box(
                    Modifier
                        .widthIn(max = BubbleMax)
                        .clip(RoundedCornerShape(18.dp))
                        .combinedClickable(onClick = onTap, onLongClick = onLongPress),
                ) {
                    BubbleBody(item, target, downloading, smart, onDownload, onAttachment, onLongPress, onLink)
                }
                if (item.reactions.isNotEmpty()) {
                    Row(
                        Modifier.offset(y = (-6).dp).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        item.reactions.forEach { r ->
                            // A plain Box: a clickable Surface would grow to the 48dp touch minimum.
                            Box(
                                Modifier
                                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                    .clip(CircleShape)
                                    .background(if (r.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable { onReaction(r.emoji) }
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            ) {
                                Text(if (r.count > 1) "${r.emoji} ${r.count}" else r.emoji, fontSize = 13.sp)
                            }
                        }
                    }
                }
                if (item.codeChip && item.code != null) {
                    AssistChip(
                        onClick = { onCopyCode(item.code) },
                        label = { Text("Copy code") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(AssistChipDefaults.IconSize)) },
                    )
                }
            }
        }
        if (m.status == DeliveryStatus.FAILED) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRetry).padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text("Not sent · Retry", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        } else if (item.lastInRun) {
            Row(
                Modifier.padding(horizontal = if (group && !out) 46.dp else 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Times.time(context, m.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (out) {
                    Spacer(Modifier.width(3.dp))
                    StatusIcon(m.status)
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: DeliveryStatus) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when (status) {
        DeliveryStatus.SENDING -> Icon(Icons.Default.Schedule, "Sending", Modifier.size(12.dp), tint = tint)
        DeliveryStatus.SENT -> Icon(Icons.Default.Done, "Sent", Modifier.size(13.dp), tint = tint)
        DeliveryStatus.DELIVERED -> Icon(Icons.Default.DoneAll, "Delivered", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
        else -> Unit
    }
}

@Composable
private fun BubbleBody(
    item: MessageUi,
    target: Language,
    downloading: Boolean,
    smart: SmartLinks,
    onDownload: () -> Unit,
    onAttachment: (Attachment) -> Unit,
    onLongPress: () -> Unit,
    onLink: (SmartEntity, List<SmartEntity>) -> Unit,
) {
    val m = item.message
    val out = m.outgoing
    val failed = m.status == DeliveryStatus.FAILED
    val bg = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        out -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fg = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        out -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val text = item.displayText
    val emojiOnly = m.attachments.isEmpty() && text.length <= 12 && Reactions.isEmoji(text.trim()) && !m.mmsPending
    // Text hidden while its translation is on its way gets no links, so a tap can't open one unseen.
    val links = rememberLinks(smart, item, enabled = !emojiOnly && !(item.awaiting && !item.showOriginal))

    Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
        if (m.mmsPending) {
            PendingMms(item, downloading, onDownload)
            return@Column
        }
        m.attachments.forEach { a ->
            AttachmentView(a, onClick = { onAttachment(a) }, onLongPress = onLongPress)
            Spacer(Modifier.size(2.dp))
        }
        if (emojiOnly) {
            Text(text.trim(), fontSize = 40.sp, lineHeight = 46.sp, modifier = Modifier.padding(horizontal = 4.dp))
        } else if (text.isNotBlank() || !m.subject.isNullOrBlank()) {
            Box(Modifier.background(bg, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                BubbleText(item, fg, target, links) { onLink(it, links) }
            }
        }
    }
}

@Composable
private fun BubbleText(item: MessageUi, color: Color, target: Language, links: List<SmartEntity>, onLink: (SmartEntity) -> Unit) {
    val m = item.message
    val tr = item.translation
    // A message awaiting its translation is drawn as placeholder bars until tapped.
    val hidden = item.awaiting && !item.showOriginal
    // A trailing marker tells the user what tapping will do: flag = translated, globe = translate / show translation.
    val marker: String? = when {
        item.awaiting -> null
        tr != null && tr.origin == TranslationOrigin.SAME -> null
        item.showingTranslation -> "flag"
        tr != null || item.translatable -> "tr"
        else -> null
    }
    val textColor = if (hidden) Color.Transparent else color
    val currentOnLink by rememberUpdatedState(onLink)
    val content = remember(item.displayText, m.subject, marker, hidden, color, links) {
        buildAnnotatedString {
            m.subject?.takeIf { it.isNotBlank() }?.let {
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); append(it); pop()
                if (item.displayText.isNotBlank()) append('\n')
            }
            if (hidden) append(item.displayText) else appendLinks(item.displayText, links, color) { currentOnLink(it) }
            if (marker != null) {
                append("  ")
                appendInlineContent(marker, "·")
            }
        }
    }
    val inline = mapOf(
        "flag" to InlineTextContent(Placeholder(13.sp, 13.sp, PlaceholderVerticalAlign.TextCenter)) {
            Text(target.flag, fontSize = 11.sp, modifier = Modifier.alpha(0.85f))
        },
        "tr" to InlineTextContent(Placeholder(13.sp, 13.sp, PlaceholderVerticalAlign.TextCenter)) {
            Icon(Icons.Default.Translate, "Translate", tint = color.copy(alpha = 0.6f))
        },
    )
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val redaction = if (hidden) {
        val pulse = rememberInfiniteTransition(label = "pending")
        val alpha by pulse.animateFloat(0.18f, 0.36f, infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "alpha")
        Modifier
            .clearAndSetSemantics { contentDescription = "Translating. Tap to show the original." }
            .drawBehind { layout?.let { drawRedaction(it, color.copy(alpha = alpha)) } }
    } else {
        Modifier
    }
    Text(
        content, color = textColor, style = MaterialTheme.typography.bodyLarge, inlineContent = inline,
        onTextLayout = { layout = it }, modifier = redaction,
    )
}

/** One rounded bar per line of text, in place of the words. */
private fun DrawScope.drawRedaction(layout: TextLayoutResult, color: Color) {
    for (line in 0 until layout.lineCount) {
        val left = layout.getLineLeft(line)
        val right = layout.getLineRight(line)
        if (right - left < 1f) continue
        val top = layout.getLineTop(line)
        val height = (layout.getLineBottom(line) - top) * 0.62f
        val y = top + (layout.getLineBottom(line) - top - height) / 2
        drawRoundRect(color, Offset(minOf(left, right), y), Size(abs(right - left), height), CornerRadius(height / 2))
    }
}

/** Tappable parts of a bubble's text: links and codes at once, the classifier's finds once it answers. */
@Composable
private fun rememberLinks(smart: SmartLinks, item: MessageUi, enabled: Boolean): List<SmartEntity> {
    val m = item.message
    val text = item.displayText
    // Keyed on the text, so a toggle between original and translation never pairs one text with the other's offsets.
    val state = remember(text, m.date) { mutableStateOf(smart.cached(text, m.date) ?: smart.quick(text, item.code)) }
    if (enabled && text.isNotBlank()) {
        LaunchedEffect(text, m.date) { state.value = smart.detect(text, m.date, item.code) }
    }
    return state.value
}

private fun AnnotatedString.Builder.appendLinks(text: String, links: List<SmartEntity>, color: Color, onLink: (SmartEntity) -> Unit) {
    val plain = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
    val code = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold))
    var last = 0
    for (e in links) {
        if (e.start < last || e.end > text.length) continue
        append(text.substring(last, e.start))
        val link = when (e) {
            is SmartEntity.Url -> LinkAnnotation.Url(e.url, plain)
            is SmartEntity.Code -> LinkAnnotation.Clickable("code", code) { onLink(e) }
            else -> LinkAnnotation.Clickable(e::class.simpleName.orEmpty(), plain) { onLink(e) }
        }
        withLink(link) { append(text.substring(e.start, e.end)) }
        last = e.end
    }
    append(text.substring(last))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentView(a: Attachment, onClick: () -> Unit, onLongPress: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    when {
        a.pending -> Surface(
            shape = shape, color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.widthIn(max = 240.dp).combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                val kind = when {
                    a.isImage -> "photo"
                    a.isVideo -> "video"
                    else -> "attachment"
                }
                Text("Waiting for $kind…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        a.isImage || a.isVideo -> Box(
            Modifier.widthIn(max = 240.dp).heightIn(min = 72.dp, max = 320.dp).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(model = a.uri, contentDescription = if (a.isVideo) "Video" else "Image", contentScale = ContentScale.Fit)
            if (a.isVideo) Icon(Icons.Default.PlayCircle, "Play", Modifier.size(48.dp), tint = Color.White.copy(alpha = 0.9f))
        }
        else -> Surface(
            shape = shape, color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.widthIn(max = 240.dp).combinedClickable(onClick = onClick, onLongClick = onLongPress),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        a.isAudio -> Icons.Default.AudioFile
                        a.isContactCard -> Icons.Default.ContactPage
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    null, tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    a.name ?: a.mimeType, maxLines = 1, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun PendingMms(item: MessageUi, downloading: Boolean, onDownload: () -> Unit) {
    val m = item.message
    val context = LocalContext.current
    val expired = false
    Surface(
        onClick = onDownload, enabled = !downloading && !expired, shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (downloading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(if (expired) Icons.Default.ErrorOutline else Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    when {
                        expired -> "Message expired"
                        downloading -> "Downloading…"
                        else -> "Tap to download"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (m.sizeBytes > 0) {
                    Text(Formatter.formatShortFileSize(context, m.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
