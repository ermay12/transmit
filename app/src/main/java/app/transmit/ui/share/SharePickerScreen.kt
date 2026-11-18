package app.transmit.ui.share

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.transmit.AppContainer
import app.transmit.Share
import app.transmit.data.ContactInfo
import app.transmit.data.PhoneContact
import app.transmit.data.chatTitle
import app.transmit.data.db.ChatSettingsEntity
import app.transmit.gm.NotConnectedException
import app.transmit.messaging.OutgoingAttachment
import app.transmit.messaging.PhoneNumbers
import app.transmit.ui.components.Avatar
import app.transmit.ui.components.Times
import app.transmit.ui.newchat.RecipientRow
import app.transmit.ui.newchat.looksLikeNumber
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A conversation the share can go to. */
data class ShareChat(
    val threadId: Long,
    val title: String,
    val people: List<ContactInfo>,
    val photo: Uri?,
    val date: Long,
    val archived: Boolean,
) {
    val isGroup get() = people.size > 1
    /** The other person's address, for one-to-one chats. */
    val address get() = people.singleOrNull()?.address
}

class SharePickerViewModel(private val c: AppContainer) : ViewModel() {
    /** Newest first; null until loaded. */
    val chats = MutableStateFlow<List<ShareChat>?>(null)
    val contacts = MutableStateFlow<List<PhoneContact>?>(null)
    /** Finding or creating the chat for a contact, which can need the phone. */
    val opening = MutableStateFlow(false)
    private val _opened = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** Conversations to open, once a contact's chat is found. */
    val opened: SharedFlow<Long> = _opened
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors

    init {
        viewModelScope.launch { contacts.value = c.contacts.phoneContacts() }
        viewModelScope.launch {
            combine(c.messaging.changes.onStart { emit(Unit) }, c.dao.allChatSettings(), c.contacts.version) { _, settings, _ -> settings }
                .collectLatest { settings ->
                    // A screenful first so a long history doesn't hold up the list.
                    if (chats.value == null) chats.value = load(settings, FIRST_PAGE)
                    chats.value = load(settings, Int.MAX_VALUE)
                }
        }
    }

    private suspend fun load(settings: List<ChatSettingsEntity>, limit: Int): List<ShareChat> = withContext(Dispatchers.IO) {
        val byThread = settings.associateBy { it.threadId }
        val list = c.messaging.conversations(limit).filter { it.recipients.isNotEmpty() }
        c.contacts.prefetch(list.flatMap { it.recipients })
        list.map { conv ->
            val people = conv.recipients.map(c.contacts::lookup)
            val s = byThread[conv.threadId]
            ShareChat(
                threadId = conv.threadId,
                title = chatTitle(people, s?.name),
                people = people,
                photo = if (people.size == 1) people[0].photoUri else conv.groupPhoto,
                date = conv.date,
                archived = s?.archived(conv.date) == true,
            )
        }.sortedByDescending { it.date }
    }

    /** Opens the chat with [address]: the existing one if there is one, else a new one on the phone. */
    fun openAddress(address: String) {
        if (opening.value) return
        opening.value = true
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { c.messaging.threadIdFor(listOf(address)) } }
                .onSuccess { _opened.tryEmit(it) }
                .onFailure { e ->
                    _errors.tryEmit(
                        if (e is NotConnectedException) "Connect to Google Messages to start a new chat"
                        else e.message ?: "Couldn't open the chat",
                    )
                }
            opening.value = false
        }
    }

    private companion object {
        const val FIRST_PAGE = 30
    }
}

/**
 * Where to send content shared from another app: recent chats first (groups included), then
 * contacts, with one search over both. Picking one opens the chat with the content ready to send.
 */
@Composable
fun SharePickerScreen(
    vm: SharePickerViewModel,
    /** Null only while the screen leaves, after the share has been handed to a chat. */
    share: Share?,
    onCancel: () -> Unit,
    onPick: (threadId: Long) -> Unit,
    onNewGroup: () -> Unit,
) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val opening by vm.opening.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    BackHandler(onBack = onCancel)
    LaunchedEffect(vm) { vm.opened.collect(onPick) }
    LaunchedEffect(vm) { vm.errors.collect { snackbar.showSnackbar(it) } }
    SharePicker(chats, contacts, opening, share, snackbar, onCancel, onPick, vm::openAddress, onNewGroup)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharePicker(
    chats: List<ShareChat>?,
    contacts: List<PhoneContact>?,
    opening: Boolean,
    share: Share?,
    snackbar: SnackbarHostState,
    onCancel: () -> Unit,
    onPick: (threadId: Long) -> Unit,
    onPickAddress: (String) -> Unit,
    onNewGroup: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val q = query.trim().lowercase()
    val digits = q.filter { it.isDigit() }
    val typed = query.trim().takeIf { looksLikeNumber(it) }
    // Archived chats stay out of the way unless searched for.
    val shownChats = chats.orEmpty().filter { chat ->
        if (q.isEmpty()) !chat.archived
        else chat.title.lowercase().contains(q) ||
            chat.people.any { it.name?.lowercase()?.contains(q) == true } ||
            (digits.length >= 3 && chat.people.any { it.address.filter(Char::isDigit).contains(digits) })
    }
    // Someone with a chat above isn't listed again under contacts.
    val chatted = shownChats.mapNotNullTo(HashSet()) { it.address?.let(PhoneNumbers::matchKey) }
    val shownContacts = contacts.orEmpty().filter { contact ->
        PhoneNumbers.matchKey(contact.number) !in chatted &&
            (q.isEmpty() || contact.name.lowercase().contains(q) || (digits.length >= 2 && contact.number.filter(Char::isDigit).contains(digits)))
    }

    fun pickBest() {
        when {
            typed != null -> onPickAddress(PhoneNumbers.normalize(typed))
            shownChats.isNotEmpty() -> onPick(shownChats.first().threadId)
            shownContacts.isNotEmpty() -> onPickAddress(shownContacts.first().number)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send to") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // The whole screen, not just the list, so messages show above the keyboard.
        modifier = Modifier.imePadding(),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            share?.let { SharePreview(it) }
            SearchField(query, onQuery = { query = it }, onGo = ::pickBest)
            if (opening) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp)) else Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                typed?.let { number ->
                    item("typed") {
                        RecipientRow(name = "Send to ${PhoneNumbers.format(number)}", detail = null, seed = number, photo = null, icon = true, enabled = !opening) {
                            onPickAddress(PhoneNumbers.normalize(number))
                        }
                    }
                }
                if (q.isEmpty()) {
                    item("new-group") { NewGroupRow(enabled = !opening, onClick = onNewGroup) }
                }
                if (chats == null) {
                    item("loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                } else if (shownChats.isNotEmpty()) {
                    item("chats-header") { SectionHeader(if (q.isEmpty()) "Recent chats" else "Chats") }
                    items(shownChats, key = { "chat:${it.threadId}" }) { chat ->
                        ChatRow(chat, enabled = !opening) { onPick(chat.threadId) }
                    }
                }
                if (shownContacts.isNotEmpty()) {
                    item("contacts-header") { SectionHeader("Contacts") }
                    items(shownContacts, key = { "contact:${it.contactId}:${it.number}" }) { contact ->
                        RecipientRow(
                            contact.name, "${PhoneNumbers.format(contact.number)} · ${contact.typeLabel}", contact.number, contact.photoUri,
                            icon = false, enabled = !opening,
                        ) { onPickAddress(contact.number) }
                    }
                }
                if (chats != null && contacts != null && shownChats.isEmpty() && shownContacts.isEmpty() && typed == null) {
                    item("empty") {
                        Text(
                            if (q.isEmpty()) "No chats or contacts yet. Type a phone number above." else "No chats or contacts match “${query.trim()}”",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onGo: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(48.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search chats, contacts or a number", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                }
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onGo() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Clear") }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun NewGroupRow(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.GroupAdd, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Text("New group", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ChatRow(chat: ShareChat, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(chat.title, chat.people.first().address, chat.photo, size = 40.dp, group = chat.isGroup)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(chat.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Enough to tell apart two chats with the same name.
            val detail = chat.address?.let { PhoneNumbers.format(it).takeIf { number -> number != chat.title } }
                ?: "${chat.people.size + 1} people".takeIf { chat.isGroup }
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(Times.short(context, chat.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What's being shared: photo and video thumbnails, and the text. */
@Composable
fun SharePreview(share: Share) {
    Surface(
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (share.attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val shown = share.attachments.take(MAX_THUMBNAILS)
                    shown.forEachIndexed { i, a ->
                        val more = if (i == shown.lastIndex) share.attachments.size - shown.size else 0
                        Thumbnail(a, more)
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            val text = share.text?.trim()?.takeIf { it.isNotEmpty() }
            Text(
                text ?: describe(share.attachments),
                style = MaterialTheme.typography.bodyMedium,
                color = if (text != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (share.attachments.isEmpty()) 3 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private const val MAX_THUMBNAILS = 3

@Composable
private fun Thumbnail(a: OutgoingAttachment, more: Int) {
    val video = a.mimeType.startsWith("video/")
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        // Shows through when the file can't be drawn; a video has its play badge instead.
        if (!video && more == 0) Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (a.mimeType.startsWith("image/") || video) {
            AsyncImage(model = a.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp))
        }
        if (video && more == 0) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "Video", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        if (more > 0) {
            Box(Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Text("+$more", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun describe(attachments: List<OutgoingAttachment>): String {
    val photos = attachments.count { it.mimeType.startsWith("image/") }
    val videos = attachments.count { it.mimeType.startsWith("video/") }
    return when {
        attachments.size == 1 && photos == 1 -> "Photo"
        attachments.size == 1 && videos == 1 -> "Video"
        photos == attachments.size -> "$photos photos"
        videos == attachments.size -> "$videos videos"
        attachments.size == 1 -> "1 file"
        else -> "${attachments.size} files"
    }
}
