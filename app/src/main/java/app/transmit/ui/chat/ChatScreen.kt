package app.transmit.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.transmit.data.ContactInfo
import app.transmit.data.chatTitle
import app.transmit.smart.SmartActions
import app.transmit.smart.SmartEntity
import app.transmit.messaging.Attachment
import app.transmit.messaging.PhoneNumbers
import app.transmit.ui.components.Avatar
import app.transmit.ui.components.Times
import app.transmit.ui.media.ImageViewer
import app.transmit.ui.media.MediaActions
import app.transmit.ui.pairing.BridgeBanner
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(vm: ChatViewModel, onBack: () -> Unit, onPair: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by vm.items.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val compose by vm.compose.collectAsStateWithLifecycle()
    val sheet by vm.sheet.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val recipients by vm.recipients.collectAsStateWithLifecycle()
    val budgetPrompt by vm.budgetPrompt.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val spent by vm.spent.collectAsStateWithLifecycle()
    val bridge by vm.bridge.collectAsStateWithLifecycle()
    val script by vm.script.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) vm.addAttachments(uris)
    }
    val listState = rememberLazyListState()
    val group = recipients.size > 1
    val target = chat?.target ?: settings?.target

    LifecycleResumeEffect(vm) {
        vm.active = true
        onPauseOrDispose { vm.active = false }
    }
    LaunchedEffect(vm) {
        vm.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    // Follow the conversation when a new message arrives while the user is near the bottom.
    val newest = items.lastOrNull()
    LaunchedEffect(newest?.key) {
        if (newest != null && (newest.message.outgoing || listState.firstVisibleItemIndex <= 2)) listState.animateScrollToItem(0)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= listState.layoutInfo.totalItemsCount - 10) vm.loadMore() }
    }

    Scaffold(
        topBar = {
            ChatTopBar(
                people = recipients.map { people[it] ?: ContactInfo(it, null, null, null) },
                customName = chat?.overrides?.name,
                autoTranslate = chat?.autoTranslate == true,
                flag = target?.flag ?: "🌐",
                onBack = onBack,
                onInfo = { vm.sheet.value = Sheet.ChatInfo },
                onToggleTranslate = vm::toggleAutoTranslate,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!loaded) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else if (target != null) {
                    LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                        item("bottom-gap") { Spacer(Modifier.height(8.dp)) }
                        val list = items.asReversed()
                        list.forEach { item ->
                            item(item.key, contentType = "message") {
                                MessageBubble(
                                    item = item,
                                    group = group,
                                    sender = item.message.sender?.let { people[it] ?: ContactInfo(it, null, null, null) },
                                    target = target,
                                    downloading = item.key in downloading,
                                    smart = vm.smart,
                                    onTap = { vm.toggleOriginal(item) },
                                    onLongPress = { vm.sheet.value = Sheet.Actions(item) },
                                    onReaction = { vm.react(item, it) },
                                    onRetry = { vm.retry(item.message) },
                                    onDownload = { vm.download(item.message) },
                                    onAttachment = { a ->
                                        if (a.isImage) viewing = a else scope.launch { MediaActions.open(context, a) }
                                    },
                                    onLink = { link, all ->
                                        val from = item.message.sender?.let { (people[it] ?: ContactInfo(it, null, null, null)).displayName }
                                        openLink(context, link, all, item, from)
                                    },
                                    onCopyCode = { SmartActions.copyCode(context, it) },
                                    onRomanize = { vm.romanizeOnSight(item.key) },
                                )
                            }
                            if (item.newDay) {
                                item("day-${item.key}", contentType = "day") {
                                    Text(
                                        Times.day(context, item.message.date),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            banner?.let { text ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Savings, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(10.dp))
                        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
                        IconButton(onClick = { vm.banner.value = null }) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp)) }
                    }
                }
            }
            BridgeBanner(bridge, onPair = onPair, onUseHere = vm::useHere)
            val s = settings
            if (s != null && target != null) {
                ComposeBar(
                    state = compose,
                    native = s.native,
                    target = target,
                    script = script,
                    // Drafting and practice work offline; only sending needs the phone.
                    enabled = true,
                    onNative = vm::onNativeChange,
                    onTarget = vm::onTargetChange,
                    onClearTarget = vm::clearTarget,
                    onAi = { vm.onAi() },
                    onSend = { vm.send() },
                    onAttach = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    onRemoveAttachment = vm::removeAttachment,
                    onApply = vm::applyCorrection,
                    onDismissFeedback = vm::dismissFeedback,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }
    }

    when (val s = sheet) {
        is Sheet.Actions -> ActionsSheet(
            item = s.item,
            onDismiss = { vm.sheet.value = null },
            onReact = { vm.react(s.item, it) },
            onMoreEmoji = { vm.sheet.value = Sheet.Emoji(s.item) },
            onExplain = { vm.sheet.value = null; vm.explain(s.item) },
            onToggle = { vm.sheet.value = null; vm.setShowOriginal(s.item, !s.item.showOriginal) },
            onRetranslate = { vm.sheet.value = null; vm.translate(s.item.message, refresh = true) },
            onDetails = { vm.sheet.value = Sheet.Details(s.item) },
            onRetry = { vm.retry(s.item.message) },
            onDelete = { vm.delete(s.item.message) },
            onCopyCode = { SmartActions.copyCode(context, it) },
        )
        is Sheet.Emoji -> EmojiSheet(onDismiss = { vm.sheet.value = null }, onPick = { vm.react(s.item, it) })
        is Sheet.Details -> DetailsSheet(s.item, people, recipients, vm.simLabel(s.item.message.subId), onDismiss = { vm.sheet.value = null })
        is Sheet.Explain -> ExplainSheet(s, onDismiss = { vm.sheet.value = null }, onRefresh = vm::refreshExplanation)
        Sheet.ChatInfo -> {
            val c = chat
            val st = settings
            if (c != null && st != null) {
                ChatInfoSheet(
                    chat = c, settings = st,
                    people = recipients.map { people[it] ?: ContactInfo(it, null, null, null) },
                    spent = spent,
                    onDismiss = { vm.sheet.value = null },
                    onAutoTranslate = vm::setAutoTranslate,
                    onLanguage = vm::setChatLanguage,
                    onBudget = vm::setChatBudget,
                    onPinned = vm::setPinned,
                    onMuted = vm::setMuted,
                    onRename = vm::setChatName,
                    onScriptDisplay = vm::setScriptDisplay,
                    onDelete = { confirmDelete = true },
                )
            }
        }
        null -> Unit
    }

    budgetPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { vm.budgetPrompt.value = null },
            icon = { Icon(Icons.Outlined.Savings, null) },
            title = { Text("Budget reached") },
            text = { Text(prompt.message) },
            confirmButton = {
                TextButton(onClick = { vm.budgetPrompt.value = null; prompt.retry() }) { Text("Allow once") }
            },
            dismissButton = { TextButton(onClick = { vm.budgetPrompt.value = null }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Delete this conversation?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.sheet.value = null
                    vm.deleteConversation(onBack)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    viewing?.let { ImageViewer(it) { viewing = null } }
}

/** Opens what a tapped link in [item] points at. [all] is every link in the bubble. */
private fun openLink(context: Context, link: SmartEntity, all: List<SmartEntity>, item: MessageUi, from: String?) {
    when (link) {
        is SmartEntity.Code -> SmartActions.copyCode(context, link.code)
        is SmartEntity.Address -> SmartActions.openMap(context, link.address)
        is SmartEntity.Phone -> SmartActions.dial(context, link.number)
        is SmartEntity.Email -> SmartActions.email(context, link.address)
        is SmartEntity.Date -> {
            val text = item.displayText
            val description = buildString {
                if (from != null) append(from).append(": ")
                append(item.message.body)
                if (item.showingTranslation) append("\n\n").append(text)
            }
            val location = all.firstNotNullOfOrNull { (it as? SmartEntity.Address)?.address }
            SmartActions.addToCalendar(context, link.time, SmartActions.eventTitle(text, link.start, link.end), description, location)
        }
        // Web links open through the text's own link handling.
        is SmartEntity.Url -> Unit
    }
}

@Composable
private fun ChatTopBar(
    people: List<ContactInfo>,
    customName: String?,
    autoTranslate: Boolean,
    flag: String,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onToggleTranslate: () -> Unit,
) {
    val context = LocalContext.current
    val title = chatTitle(people, customName)
    val first = people.firstOrNull()
    // A renamed chat keeps its people in view under the name.
    val subtitle = when {
        first == null -> null
        people.size > 1 -> if (customName != null) chatTitle(people) else "${people.size} people"
        customName != null && first.isContact -> "${first.displayName} · ${PhoneNumbers.format(first.address)}"
        customName != null || first.isContact -> PhoneNumbers.format(first.address)
        else -> null
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(60.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onInfo).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(title, first?.address ?: title, if (people.size == 1) first?.photoUri else null, size = 36.dp, group = people.size > 1)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // Auto-translate toggle: the practice-language flag, lit when on.
            Surface(
                onClick = onToggleTranslate,
                shape = CircleShape,
                color = if (autoTranslate) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
                border = if (autoTranslate) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.padding(horizontal = 4.dp).size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(flag, fontSize = 18.sp, modifier = Modifier.alpha(if (autoTranslate) 1f else 0.4f))
                }
            }
            if (people.size == 1 && !PhoneNumbers.isShortCodeOrAlpha(people[0].address)) {
                IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${people[0].address}"))) }) {
                    Icon(Icons.Default.Call, "Call")
                }
            }
            IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, "Chat settings") }
        }
    }
}
