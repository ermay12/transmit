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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import app.transmit.R
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
import app.transmit.ui.access.openPlayListing
import app.transmit.ui.access.AiBlockDialog
import app.transmit.ai.AiBlock
import app.transmit.ui.access.TranslationOffStrip
import app.transmit.ui.access.TurnOnTranslationSheet
import app.transmit.ui.access.TrialUi
import app.transmit.ui.components.Avatar
import app.transmit.ui.components.DemoTag
import app.transmit.ui.components.Times
import app.transmit.ui.media.ImageViewer
import app.transmit.ui.media.MediaActions
import app.transmit.ui.pairing.BridgeBanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(vm: ChatViewModel, onBack: () -> Unit, onPair: () -> Unit, onPlans: () -> Unit, onOwnKey: () -> Unit) {
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
    val groupPhoto by vm.groupPhoto.collectAsStateWithLifecycle()
    val budgetPrompt by vm.budgetPrompt.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val spent by vm.spent.collectAsStateWithLifecycle()
    val bridge by vm.bridge.collectAsStateWithLifecycle()
    val script by vm.script.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val trial by vm.trial.state.collectAsStateWithLifecycle()
    val offStrip by vm.offStrip.collectAsStateWithLifecycle()
    val translationOff by vm.translationOff.collectAsStateWithLifecycle()
    // Model, cost and dollar budgets are for people paying for their own AI.
    val aiDetails = access?.showsAiDetails == true
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    /** The message a tapped quote just scrolled to. */
    var highlighted by remember { mutableStateOf<String?>(null) }
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
    LaunchedEffect(vm) {
        vm.jumps.collectLatest { key ->
            val index = listIndexOf(vm.items.value, key) ?: return@collectLatest
            // A third of the way up the screen, not against the compose bar.
            listState.animateScrollToItem(index, -listState.layoutInfo.viewportSize.height / 3)
            highlighted = key
            delay(1_500)
            highlighted = null
        }
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
                groupPhoto = groupPhoto,
                customName = chat?.overrides?.name,
                // With translation off the flag is never lit, and tapping it offers to turn translation on.
                autoTranslate = chat?.autoTranslate == true && !translationOff,
                flag = target?.flag ?: "🌐",
                onBack = onBack,
                onInfo = { vm.sheet.value = Sheet.ChatInfo },
                onToggleTranslate = { if (translationOff) vm.offerTranslation() else vm.toggleAutoTranslate() },
                demo = vm.demo,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (offStrip) {
                TranslationOffStrip(
                    canBuy = vm.canBuy,
                    starting = trial == TrialUi.Starting,
                    onAction = { if (vm.canBuy) vm.startTrialFromStrip() else openPlayListing(context) },
                    onDismiss = vm::dismissOffStrip,
                )
            }
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
                                    onReply = if (item.message.replyable) ({ vm.replyTo(item) }) else null,
                                    quoteAuthor = item.quote?.let { quoteAuthor(it, people) },
                                    onQuote = vm::showQuoted,
                                    highlighted = item.key == highlighted,
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
            banner?.let { b ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (b.budget) Icons.Outlined.Savings else Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(10.dp))
                        // The next step follows the text, as on the translation-off strip: Upgrade to Plus opens the plans.
                        val upgrade = stringResource(R.string.plan_upgrade)
                        Text(
                            buildAnnotatedString {
                                append(b.text)
                                b.note?.let { append(" · "); append(it) }
                                if (b.upgrade) {
                                    append(" · ")
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) { append(upgrade) }
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f).clickable(enabled = b.upgrade, onClick = onPlans).padding(vertical = 10.dp),
                        )
                        IconButton(onClick = { vm.banner.value = null }) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp)) }
                    }
                }
            }
            BridgeBanner(bridge, onPair = onPair, onUseHere = vm::useHere)
            val s = settings
            if (s != null && target != null) {
                // The quote follows the message's bubble: its translation arriving, or a switch to the original.
                val reply = compose.replyTo?.let { r -> items.firstOrNull { it.key == r.key }?.toQuote() ?: r }
                ComposeBar(
                    state = compose.copy(replyTo = reply),
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
                    replyAuthor = reply?.let { quoteAuthor(it, people) },
                    onCancelReply = vm::cancelReply,
                    modifier = Modifier.navigationBarsPadding(),
                    plain = translationOff,
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
            onReply = if (s.item.message.replyable) ({ vm.replyTo(s.item) }) else null,
            onExplain = { vm.sheet.value = null; vm.explain(s.item) },
            onRetranslate = {
                vm.sheet.value = null
                if (translationOff) vm.offerTranslation { vm.translate(s.item.message, refresh = true) } else vm.translate(s.item.message, refresh = true)
            },
            onDetails = { vm.sheet.value = Sheet.Details(s.item) },
            onRetry = { vm.retry(s.item.message) },
            onDelete = { vm.delete(s.item.message) },
            onCopyCode = { SmartActions.copyCode(context, it) },
        )
        is Sheet.Emoji -> EmojiSheet(onDismiss = { vm.sheet.value = null }, onPick = { vm.react(s.item, it) })
        is Sheet.Details -> DetailsSheet(s.item, people, recipients, vm.simLabel(s.item.message.subId), aiDetails, onDismiss = { vm.sheet.value = null })
        is Sheet.Explain -> ExplainSheet(s, aiDetails, onDismiss = { vm.sheet.value = null }, onRefresh = vm::refreshExplanation)
        Sheet.ChatInfo -> {
            val c = chat
            val st = settings
            if (c != null && st != null) {
                ChatInfoSheet(
                    chat = c, settings = st,
                    people = recipients.map { people[it] ?: ContactInfo(it, null, null, null) },
                    spent = spent,
                    showBudget = aiDetails,
                    onDismiss = { vm.sheet.value = null },
                    onAutoTranslate = vm::setAutoTranslate,
                    onLanguage = vm::setChatLanguage,
                    onBudget = vm::setChatBudget,
                    onPinned = vm::setPinned,
                    onMuted = vm::setMuted,
                    onRename = vm::setChatName,
                    onScriptDisplay = vm::setScriptDisplay,
                    onDelete = { confirmDelete = true },
                    demo = vm.demo,
                )
            }
        }
        null -> Unit
    }

    // An AI call ran this month's AI out: say so, and what's next, once per allowance period.
    val usedUpPrompt by vm.usedUpPrompt.collectAsStateWithLifecycle()
    LaunchedEffect(usedUpPrompt, budgetPrompt == null) { usedUpPrompt?.let(vm::showUsedUpPrompt) }
    budgetPrompt?.let { prompt ->
        val close = { vm.budgetPrompt.value = null; vm.trial.reset() }
        val a = access
        // No AI set up, or it ended: the sheet that turns translation on, whichever translate action led here.
        if (a != null && !a.canUseAi && (prompt.block == AiBlock.NotSetUp || prompt.block is AiBlock.Ended)) {
            TurnOnTranslationSheet(
                access = a,
                canBuy = vm.canBuy,
                trial = trial,
                onStartTrial = { vm.trial.start { close(); prompt.retry() } },
                onPlans = { close(); onPlans() },
                onOwnKey = { close(); onOwnKey() },
                onDismiss = close,
            )
            return@let
        }
        AiBlockDialog(
            block = prompt.block,
            trial = trial,
            onStartTrial = { vm.trial.start { close(); prompt.retry() } },
            onPlans = { close(); onPlans() },
            onOwnKey = { close(); onOwnKey() },
            onAllowOnce = { close(); prompt.retry() },
            onUseHere = { close(); vm.useSubscriptionHere() },
            canBuy = vm.canBuy,
            onDismiss = close,
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

/** Who wrote a quoted message: "You", or their name as the chat shows it. */
private fun quoteAuthor(q: Quote, people: Map<String, ContactInfo>): String =
    if (q.outgoing) "You" else q.sender?.let { (people[it] ?: ContactInfo(it, null, null, null)).displayName }.orEmpty()

/**
 * Where message [key] is in the chat's list, which runs up from the newest: a gap at the bottom, then each
 * message with a day header above the first of each day.
 */
internal fun listIndexOf(items: List<MessageUi>, key: String): Int? {
    var index = 1
    for (item in items.asReversed()) {
        if (item.key == key) return index
        index += if (item.newDay) 2 else 1
    }
    return null
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
    groupPhoto: android.net.Uri?,
    customName: String?,
    autoTranslate: Boolean,
    flag: String,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onToggleTranslate: () -> Unit,
    /** A sample chat: labelled Demo, and its made-up people can't be called. */
    demo: Boolean = false,
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
                Avatar(title, first?.address ?: title, if (people.size == 1) first?.photoUri else groupPhoto, size = 36.dp, group = people.size > 1)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    if (demo) {
                        DemoTag(Modifier.padding(top = 2.dp))
                    } else if (subtitle != null) {
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
            if (!demo && people.size == 1 && !PhoneNumbers.isShortCodeOrAlpha(people[0].address)) {
                IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${people[0].address}"))) }) {
                    Icon(Icons.Default.Call, "Call")
                }
            }
            IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, "Chat settings") }
        }
    }
}
