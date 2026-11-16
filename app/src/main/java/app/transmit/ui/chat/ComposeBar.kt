package app.transmit.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.transmit.ai.Feedback
import app.transmit.data.Language
import app.transmit.telephony.OutgoingAttachment
import app.transmit.ui.components.Diff
import app.transmit.ui.components.MarkdownText
import app.transmit.ui.theme.LocalTransmitColors
import coil3.compose.AsyncImage

@Composable
fun ComposeBar(
    state: ComposeUi,
    native: Language,
    target: Language,
    enabled: Boolean,
    onNative: (String) -> Unit,
    onTarget: (String) -> Unit,
    onClearTarget: () -> Unit,
    onAi: () -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (OutgoingAttachment) -> Unit,
    onApply: (String) -> Unit,
    onDismissFeedback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).animateContentSize()) {
            AnimatedVisibility(state.feedback != null) {
                state.feedback?.let { FeedbackCard(it, state.target, onApply, onDismissFeedback) }
            }
            if (state.attachments.isNotEmpty()) AttachmentStrip(state.attachments, onRemoveAttachment)
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, enabled = enabled) { Icon(Icons.Default.AddPhotoAlternate, "Attach") }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LanguageField(
                        value = state.target, onValue = onTarget, language = target,
                        tint = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                        ai = state.targetAi, stale = state.stale, enabled = enabled,
                        onClear = onClearTarget, onRefresh = onAi,
                    )
                    LanguageField(
                        value = state.native, onValue = onNative, language = native,
                        tint = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ai = state.nativeAi, stale = false, enabled = enabled,
                        onClear = { onNative("") }, onRefresh = null,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AiButton(state, enabled, onAi)
                    SendButton(state, enabled, onSend)
                }
            }
        }
    }
}

@Composable
private fun LanguageField(
    value: String,
    onValue: (String) -> Unit,
    language: Language,
    tint: Color,
    ai: Boolean,
    stale: Boolean,
    enabled: Boolean,
    onClear: () -> Unit,
    onRefresh: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(tint).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(language.flag, fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (value.isEmpty()) {
                Text(language.nativeName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            BasicTextField(
                value = value, onValueChange = onValue, enabled = enabled, maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            stale && onRefresh != null -> IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, "Update translation", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            ai && value.isNotEmpty() -> Icon(Icons.Default.AutoAwesome, "Written by AI", Modifier.padding(6.dp).size(16.dp), tint = LocalTransmitColors.current.aiTint)
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Clear", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AiButton(state: ComposeUi, enabled: Boolean, onAi: () -> Unit) {
    val mode = state.aiMode
    val busy = state.busy.let { it == Busy.TRANSLATE || it == Busy.FEEDBACK || it == Busy.EXPLAIN }
    FilledTonalIconButton(
        onClick = onAi,
        enabled = enabled && mode != AiMode.NONE && state.busy == null,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onTertiaryContainer)
        } else {
            when (mode) {
                AiMode.TRANSLATE, AiMode.NONE -> Icon(Icons.Default.Translate, "Translate")
                AiMode.FEEDBACK -> Icon(Icons.Default.Spellcheck, "Check my translation")
                AiMode.EXPLAIN -> Icon(Icons.Outlined.Lightbulb, "Explain translation")
            }
        }
    }
}

@Composable
private fun SendButton(state: ComposeUi, enabled: Boolean, onSend: () -> Unit) {
    val needsNative = state.native.isBlank() && state.target.isNotBlank()
    FilledIconButton(
        onClick = onSend,
        enabled = enabled && state.canSend,
        modifier = Modifier.size(44.dp),
        colors = if (state.readyToSend) {
            IconButtonDefaults.filledIconButtonColors(containerColor = LocalTransmitColors.current.good, contentColor = Color.White)
        } else IconButtonDefaults.filledIconButtonColors(),
    ) {
        if (state.busy == Busy.SEND) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            // When only the target box is filled, the first tap prepares the native text to send.
            Icon(Icons.AutoMirrored.Filled.Send, if (needsNative) "Prepare message" else "Send")
        }
    }
}

@Composable
private fun AttachmentStrip(items: List<OutgoingAttachment>, onRemove: (OutgoingAttachment) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 48.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { a ->
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                if (a.mimeType.startsWith("image/") || a.mimeType.startsWith("video/")) {
                    AsyncImage(model = a.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.align(Alignment.Center))
                }
                Icon(
                    Icons.Default.Cancel, "Remove",
                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape).clickable { onRemove(a) },
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun FeedbackCard(fb: Feedback, attempt: String, onApply: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalTransmitColors.current
    var expanded by remember(fb) { mutableStateOf(fb.verdict == Feedback.Verdict.FIX) }
    val (icon, tint) = when (fb.verdict) {
        Feedback.Verdict.PERFECT -> Icons.Default.CheckCircle to colors.good
        Feedback.Verdict.GOOD -> Icons.Default.ThumbUp to colors.good
        Feedback.Verdict.FIX -> Icons.Outlined.Edit to colors.warn
    }
    Surface(
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, bottom = 6.dp),
    ) {
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, fb.verdict.name, Modifier.padding(top = 2.dp).size(20.dp), tint = tint)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    val corrected = fb.corrected.ifBlank { attempt }
                    if (fb.verdict == Feedback.Verdict.PERFECT || corrected.trim() == attempt.trim()) {
                        Text(corrected, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        // Diffs against the live draft, so the highlights shrink as the user types the fixes in.
                        val diff = remember(attempt, corrected, colors.good, colors.bad) {
                            Diff.annotated(attempt, corrected, add = colors.good, del = colors.bad)
                        }
                        Text(diff, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (fb.verdict != Feedback.Verdict.PERFECT && fb.corrected.isNotBlank() && fb.corrected.trim() != attempt.trim()) {
                    IconButton(onClick = { onApply(fb.corrected) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Check, "Use correction", tint = colors.good)
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp)) }
            }
            if (fb.natural.isNotBlank() && fb.natural.trim() != fb.corrected.trim()) {
                Row(Modifier.padding(start = 28.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, "More natural", Modifier.size(14.dp), tint = colors.aiTint)
                    Spacer(Modifier.width(6.dp))
                    Text(fb.natural, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onApply(fb.natural) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Check, "Use this", Modifier.size(18.dp), tint = colors.good)
                    }
                }
            }
            if (fb.notes.isNotBlank()) {
                Box(
                    Modifier.padding(start = 28.dp, top = 4.dp, end = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded },
                ) {
                    if (expanded) {
                        MarkdownText(fb.notes, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            fb.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart('-', '*', '•', ' ').orEmpty(),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
