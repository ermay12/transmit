package app.transmit.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.transmit.ai.usd
import app.transmit.ui.components.Avatar
import app.transmit.ui.components.Times
import app.transmit.ui.rememberDefaultSmsRequest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onUsage: () -> Unit,
) {
    val items by vm.filtered.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val isDefault by vm.isDefault.collectAsStateWithLifecycle()
    val canRead by vm.canRead.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val spent by vm.spent.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val showArchived by vm.showArchived.collectAsStateWithLifecycle()
    val archivedCount by vm.archivedCount.collectAsStateWithLifecycle()
    val complete by vm.complete.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val expandedFab by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    // The list keeps its first row in place when rows appear above it (a conversation with a new
    // message, the Archived row), which hides them; at the top, stay at the top instead.
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    // Read here rather than inside the list so changes recompose this screen and reach the SideEffect.
    val list = items
    val archivedRow = !showArchived && archivedCount > 0 && query.isBlank()
    if (atTop) SideEffect { listState.requestScrollToItem(0) }
    val requestDefault = rememberDefaultSmsRequest { vm.refresh() }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }
    BackHandler(enabled = selection.isEmpty() && showArchived) { vm.showArchived.value = false }

    fun archive(threadId: Long, archived: Boolean) {
        vm.setArchived(setOf(threadId), archived)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(if (archived) "Archived" else "Moved to inbox", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) vm.setArchived(setOf(threadId), !archived)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selection.isNotEmpty()) {
                SelectionBar(
                    count = selection.size,
                    archived = showArchived,
                    onClose = vm::clearSelection,
                    onRead = vm::markSelectedRead,
                    onPin = vm::pinSelected,
                    onMute = vm::muteSelected,
                    onArchive = vm::archiveSelected,
                    onDelete = { confirmDelete = true },
                )
            } else if (showArchived) {
                ArchivedBar(onBack = { vm.showArchived.value = false })
            } else {
                SearchBar(
                    query = query,
                    onQuery = { vm.query.value = it },
                    spent = spent,
                    limit = settings?.monthlyBudgetUsd ?: 0.0,
                    onUsage = onUsage,
                    onSettings = onSettings,
                )
            }
        },
        floatingActionButton = {
            if (isDefault && !showArchived) {
                ExtendedFloatingActionButton(
                    onClick = onNew,
                    expanded = expandedFab,
                    icon = { Icon(Icons.Default.Edit, null) },
                    text = { Text("Start chat") },
                )
            }
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!isDefault && !showArchived) {
                item("default-banner") { DefaultAppBanner(onClick = requestDefault) }
            }
            if (archivedRow) {
                item("archived") { ArchivedRow(archivedCount, onClick = { vm.clearSelection(); vm.showArchived.value = true }) }
            }
            when {
                list == null && canRead -> item("loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                list.isNullOrEmpty() && complete -> item("empty") { EmptyState(query.isNotBlank(), showArchived) }
                list != null -> items(list, key = { it.threadId }) { item ->
                    // Search results stay listed after a change, so they don't swipe.
                    SwipeToArchive(archived = item.archived, enabled = query.isBlank(), onSwiped = { archive(item.threadId, !item.archived) }) {
                        ConversationRow(
                            item = item,
                            selected = item.threadId in selection,
                            onClick = { if (selection.isEmpty()) onOpen(item.threadId) else vm.toggleSelect(item.threadId) },
                            onLongClick = { vm.toggleSelect(item.threadId) },
                        )
                    }
                }
            }
            if (list != null && !complete) {
                item("loading-more") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
            item("bottom-space") { Spacer(Modifier.height(88.dp)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Delete ${selection.size} conversation${if (selection.size > 1) "s" else ""}?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSelected() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, spent: Double, limit: Double, onUsage: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f).height(48.dp)) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    BasicTextField(
                        value = query, onValueChange = onQuery, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Clear") }
                }
                SpendPill(spent, limit, onUsage)
            }
        }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
    }
}

@Composable
fun SpendPill(spent: Double, limit: Double, onClick: () -> Unit) {
    val fraction = if (limit > 0) (spent / limit).toFloat().coerceIn(0f, 1f) else 0f
    val color = when {
        fraction >= 1f -> MaterialTheme.colorScheme.error
        fraction > 0.8f -> app.transmit.ui.theme.LocalTransmitColors.current.warn
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                progress = { fraction }, modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = color,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text(usd(spent), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ArchivedBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text("Archived", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ArchivedRow(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Centered under the 44dp avatars below.
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Archive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Text("Archived", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Swiping a conversation either way archives it (or, in the archive, moves it back to the inbox). */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * Swipe a row either way to archive it (in the archive, to restore it). Hand-rolled because
 * SwipeToDismissBox claims any drag at least as sideways as it is vertical and lets the gentlest flick
 * dismiss, so a diagonal scroll could archive a chat.
 */
@Composable
private fun SwipeToArchive(archived: Boolean, enabled: Boolean, onSwiped: () -> Unit, content: @Composable () -> Unit) {
    // Not saveable: the list restores saved state by key, so a row that returns (Undo, a new message)
    // would come back already swiped.
    var offset by remember { mutableFloatStateOf(0f) }
    val direction by remember { derivedStateOf { sign(offset) } }
    val swiped by rememberUpdatedState(onSwiped)
    val scope = rememberCoroutineScope()
    val gesture = if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        var settle: Job? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!awaitSideways(down.id)) return@awaitEachGesture
            settle?.cancel()
            val tracker = VelocityTracker()
            val released = horizontalDrag(down.id) { change ->
                tracker.addPointerInputChange(change)
                offset += change.positionChange().x
                change.consume()
            }
            val width = size.width.toFloat()
            val fling = tracker.calculateVelocity().x
            // Past 40% of the width, or flung hard the way it's already going.
            val gone = released && (abs(offset) > width * 0.4f ||
                (abs(fling) > 1000.dp.toPx() && sign(fling) == sign(offset) && abs(offset) > width * 0.1f))
            settle = scope.launch {
                animate(offset, if (gone) sign(offset) * width else 0f, fling) { value, _ -> offset = value }
                // The row leaves the list once the change lands, so the offset never needs resetting.
                if (gone) swiped()
            }
        }
    }
    Box(gesture) {
        if (direction != 0f) {
            Row(
                Modifier.matchParentSize().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (direction > 0) Arrangement.Absolute.Left else Arrangement.Absolute.Right,
            ) {
                Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(if (archived) "Inbox" else "Archive", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
            }
        }
        Box(Modifier.absoluteOffset { IntOffset(offset.roundToInt(), 0) }) { content() }
    }
}

/**
 * Waits for a touch to show its direction: true once it has moved past the touch slop within about 27°
 * of horizontal; false once it has moved that far vertically (a scroll, left to the list) or ends.
 */
private suspend fun AwaitPointerEventScope.awaitSideways(id: PointerId): Boolean {
    val slop = viewConfiguration.touchSlop
    var total = Offset.Zero
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: return false
        if (!change.pressed || change.isConsumed) return false
        total += change.positionChange()
        if (abs(total.y) >= slop) return false
        if (abs(total.x) >= slop && abs(total.x) > abs(total.y) * 2) {
            change.consume()
            return true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    archived: Boolean,
    onClose: () -> Unit,
    onRead: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).windowInsetsPadding(WindowInsets.statusBars).height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cancel") }
        Text("$count", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
        IconButton(onClick = onPin) { Icon(Icons.Outlined.PushPin, "Pin") }
        IconButton(onClick = onMute) { Icon(Icons.Outlined.NotificationsOff, "Mute") }
        IconButton(onClick = onRead) { Icon(Icons.Default.DoneAll, "Mark read") }
        IconButton(onClick = onArchive) {
            if (archived) Icon(Icons.Outlined.Unarchive, "Move to inbox") else Icon(Icons.Outlined.Archive, "Archive")
        }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
    }
}

@Composable
private fun DefaultAppBanner(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Sms, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Make Transmit your SMS app", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Needed to send and receive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            FilledTonalButton(onClick = onClick) { Text("Set") }
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean, archived: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            when {
                searching -> Icons.Default.Search
                archived -> Icons.Outlined.Archive
                else -> Icons.Default.Forum
            },
            null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                searching -> "No matches"
                archived -> "No archived conversations"
                else -> "No conversations yet"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(item: ConversationUi, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val unread = item.conversation.unread
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            val first = item.people.firstOrNull()
            Avatar(
                name = item.title, seed = first?.address ?: item.title, photo = if (item.people.size == 1) first?.photoUri else null,
                size = 44.dp, group = item.people.size > 1,
            )
            if (selected) {
                Box(Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The name and its icons share one weighted slot so the time always sits at the end.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.pinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.outline)
                    if (item.muted) Icon(Icons.Default.NotificationsOff, "Muted", Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    Times.short(context, item.conversation.date), style = MaterialTheme.typography.labelSmall,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val tint = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                when {
                    item.draft != null -> {
                        Icon(Icons.Default.Edit, "Draft", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                        Text(item.draft, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    else -> {
                        if (item.conversation.snippetOutgoing) Icon(Icons.AutoMirrored.Filled.Reply, "You", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                        if (item.snippetTranslated) Icon(Icons.Default.Translate, "Translated", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
                        Text(
                            item.snippet.replace('\n', ' '), maxLines = 1, overflow = TextOverflow.Ellipsis, color = tint,
                            fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (unread) Box(Modifier.size(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}
