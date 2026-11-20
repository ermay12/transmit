package app.transmit.gm

import android.util.Base64
import android.util.Log
import androidx.annotation.VisibleForTesting
import app.transmit.data.ContactsRepository
import app.transmit.data.KeyVault
import app.transmit.data.db.GmConversationEntity
import app.transmit.data.db.GmDao
import app.transmit.data.db.GmMessageEntity
import app.transmit.data.db.TransmitDao
import app.transmit.gm.proto.AlertType
import app.transmit.gm.proto.BridgeAuth
import app.transmit.gm.proto.Conversation
import app.transmit.gm.proto.Cursor
import app.transmit.gm.proto.ListConversationsRequest
import app.transmit.gm.proto.MediaContent
import app.transmit.gm.proto.SIMPayload
import app.transmit.gm.proto.Settings
import app.transmit.messaging.Message
import app.transmit.messaging.PhoneNumbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import app.transmit.gm.proto.Message as GmMessage

sealed interface BridgeState {
    /** No phone paired: Transmit can't show or send anything. */
    data object Unpaired : BridgeState
    /**
     * Pairing in progress. With a QR code: waiting for the phone to scan [qrUrl]. Through the Google
     * account ([google]): waiting for the user to pick [emoji] on the phone. Null while being fetched.
     */
    data class Pairing(
        val qrUrl: String? = null,
        val error: String? = null,
        val google: Boolean = false,
        val emoji: String? = null,
    ) : BridgeState
    data object Connecting : BridgeState
    data class Connected(
        val phoneResponding: Boolean = true,
        /** Messages for Web is open somewhere else, so the phone is talking to it instead. */
        val inactive: Boolean = false,
        val trouble: String? = null,
    ) : BridgeState
    /** The pairing stopped working (unpaired from the phone, or expired); pair again. */
    data class LoggedOut(val reason: String) : BridgeState
}

class NotConnectedException : GmException("Not connected to Google Messages")

/**
 * Keeps Transmit attached to Google Messages on the phone: pairing, the connection, and a local
 * cache (Room) of conversations and messages that the rest of the app reads.
 */
class GmBridge(
    private val secrets: KeyVault,
    private val gmDao: GmDao,
    private val dao: TransmitDao,
    private val contacts: ContactsRepository,
    private val mediaDir: File,
    private val scope: CoroutineScope,
    /** A new incoming message arrived (not a sync of old ones). */
    private val onIncoming: suspend (threadId: Long, key: String) -> Unit,
    /** A conversation was read elsewhere (on the phone, or another Messages for Web). */
    private val onReadElsewhere: suspend (threadId: Long) -> Unit,
    private val isOnScreen: (threadId: Long) -> Boolean,
    private val onLoggedOut: (reason: String) -> Unit,
) : Link {
    private val _state = MutableStateFlow<BridgeState>(if (secrets.has(AUTH_KEY)) BridgeState.Connecting else BridgeState.Unpaired)
    override val state: StateFlow<BridgeState> = _state

    @Volatile private var client: GmClient? = null
    private val events = Channel<GmEvent>(Channel.UNLIMITED)
    private var pairingJob: Job? = null
    @Volatile private var settings: Settings? = null
    private val lifecycle = Mutex()

    private val fetchedLatest = Collections.synchronizedSet(HashSet<Long>())
    private val exhausted = Collections.synchronizedSet(HashSet<Long>())
    private val olderInFlight = Collections.synchronizedSet(HashSet<Long>())

    val isPaired get() = secrets.has(AUTH_KEY)

    /**
     * False until the phone's conversation list first arrives in this process. Until then an empty
     * cache means "still loading", not "no conversations": right after pairing that can take a while.
     */
    private val _synced = MutableStateFlow(false)
    override val synced: StateFlow<Boolean> = _synced

    init {
        scope.launch {
            for (e in events) {
                try {
                    handle(e)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Log.e(TAG, "Handling $e failed", ex)
                }
            }
        }
        // Names from the cache, so chats are titled right before the phone answers.
        scope.launch(Dispatchers.IO) {
            val cached = gmDao.conversations().mapNotNull { runCatching { Conversation.parseFrom(it.proto) }.getOrNull() }
            contacts.addNames(cached.flatMap(GmMapping::names))
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    /** Connects if paired and not connected yet. Safe to call repeatedly. */
    fun start() {
        scope.launch {
            lifecycle.withLock {
                if (client != null || _state.value is BridgeState.Pairing) return@withLock
                val auth = loadAuth() ?: run {
                    if (_state.value !is BridgeState.LoggedOut) _state.value = BridgeState.Unpaired
                    return@withLock
                }
                connect(auth)
            }
        }
    }

    private suspend fun connect(auth: GmAuth) {
        _state.value = BridgeState.Connecting
        val c = newClient(auth)
        client = c
        try {
            c.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Connect failed", e)
            if ((e as? GmHttpException)?.fatal == true) {
                loggedOut("Google Messages ended this pairing. Pair again to keep using Transmit.")
            } else {
                // Offline or a server hiccup: keep trying in the background.
                _state.value = BridgeState.Connected(trouble = "Can't reach Google Messages")
                scope.launch {
                    delay(15_000)
                    if (client === c) c.reconnect()
                }
            }
        }
    }

    private fun newClient(auth: GmAuth) = GmClient(auth, onEvent = { events.trySend(it) }, onAuthChanged = { saveAuth(auth) })

    fun startPairing() {
        pairingJob?.cancel()
        pairingJob = scope.launch {
            lifecycle.withLock {
                client?.close()
                client = null
            }
            _state.value = BridgeState.Pairing(null)
            val auth = GmAuth.fresh()
            val c = newClient(auth)
            client = c
            try {
                _state.value = BridgeState.Pairing(c.startPairing())
                // The code expires; keep showing a fresh one until the phone scans it.
                repeat(QR_REFRESHES) {
                    delay(QR_REFRESH_MS)
                    if (_state.value !is BridgeState.Pairing) return@launch
                    _state.value = BridgeState.Pairing(c.refreshPairingQr())
                }
                c.close()
                if (client === c) client = null
                _state.value = BridgeState.Pairing(null, "The code expired. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pairing failed", e)
                _state.value = BridgeState.Pairing(null, "Couldn't reach Google (${e.message})")
            }
        }
    }

    /**
     * Pairs through the Google account signed in with [cookies]: no second screen needed. The user
     * confirms by picking the emoji in [BridgeState.Pairing.emoji] in Google Messages on the phone.
     */
    fun startGooglePairing(cookies: Map<String, String>) {
        pairingJob?.cancel()
        pairingJob = scope.launch {
            lifecycle.withLock {
                client?.close()
                client = null
            }
            _state.value = BridgeState.Pairing(google = true)
            val auth = GmAuth.fresh().also { it.cookies.putAll(cookies) }
            val c = newClient(auth)
            client = c
            try {
                val pairing = c.startGooglePairing()
                _state.value = BridgeState.Pairing(google = true, emoji = pairing.emoji)
                c.finishGooglePairing(pairing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Google pairing failed", e)
                c.close()
                if (client === c) client = null
                val reason = (e as? GmHttpException)?.takeIf { it.code == 401 || it.code == 403 }
                    ?.let { "Google didn't accept the sign-in. Sign in again." }
                    ?: e.message ?: "Pairing failed"
                _state.value = BridgeState.Pairing(google = true, error = reason)
            }
        }
    }

    fun cancelPairing() {
        val pairing = _state.value as? BridgeState.Pairing ?: return
        pairingJob?.cancel()
        val c = client
        client = null
        if (c != null) {
            scope.launch {
                // Tell the phone, so it stops asking the user to confirm.
                if (pairing.google) runCatching { c.cancelGooglePairing() }
                c.close()
            }
        }
        _state.value = if (isPaired) BridgeState.Connecting else BridgeState.Unpaired
        if (isPaired) start()
    }

    /** Removes the pairing from the phone and forgets everything cached from it. */
    suspend fun unpair() {
        val c = client
        runCatching { c?.unpair() }.onFailure { Log.w(TAG, "Unpair request failed", it) }
        c?.close()
        client = null
        forget()
        _state.value = BridgeState.Unpaired
    }

    private suspend fun forget() {
        secrets.put(AUTH_KEY, null)
        _synced.value = false
        gmDao.clearMessages()
        gmDao.clearConversations()
        fetchedLatest.clear()
        exhausted.clear()
        mediaDir.deleteRecursively()
    }

    private suspend fun loggedOut(reason: String) {
        client?.close()
        client = null
        secrets.put(AUTH_KEY, null)
        _state.value = BridgeState.LoggedOut(reason)
        onLoggedOut(reason)
    }

    /** Makes this the active Messages for Web session again after another took over. */
    override fun useHere() {
        val c = client ?: return
        scope.launch {
            runCatching { c.claimActiveSession() }
            _state.update { if (it is BridgeState.Connected) it.copy(inactive = false) else it }
        }
    }

    fun requireClient(): GmClient {
        val c = client
        if (c == null || _state.value !is BridgeState.Connected) throw NotConnectedException()
        return c
    }

    private fun loadAuth(): GmAuth? {
        val stored = secrets.get(AUTH_KEY) ?: return null
        return runCatching { GmAuth.fromProto(BridgeAuth.parseFrom(Base64.decode(stored, Base64.NO_WRAP))) }
            .onFailure { Log.e(TAG, "Stored pairing is unreadable", it) }
            .getOrNull()?.takeIf { it.isPaired }
    }

    private fun saveAuth(auth: GmAuth) {
        if (auth.isPaired) secrets.put(AUTH_KEY, Base64.encodeToString(auth.toProto().toByteArray(), Base64.NO_WRAP))
    }

    // ---- events ----------------------------------------------------------------------------------

    @VisibleForTesting
    internal suspend fun handle(e: GmEvent) {
        when (e) {
            is GmEvent.Paired -> {
                pairingJob?.cancel()
                client?.let { saveAuth(it.auth) }
                // A different phone means different conversations.
                _synced.value = false
                gmDao.clearMessages()
                gmDao.clearConversations()
                _state.value = BridgeState.Connecting
            }
            GmEvent.Ready -> {
                _state.update { (it as? BridgeState.Connected)?.copy(trouble = null) ?: BridgeState.Connected() }
                scope.launch { initialSync() }
            }
            GmEvent.Unpaired -> loggedOut("Transmit was unpaired from Google Messages on your phone.")
            is GmEvent.Fatal -> loggedOut("Google Messages ended this pairing. Pair again to keep using Transmit.")
            is GmEvent.ConnectionTrouble -> _state.update {
                if (it is BridgeState.Connected) it.copy(trouble = "Reconnecting…") else it
            }
            GmEvent.ConnectionRestored -> _state.update { if (it is BridgeState.Connected) it.copy(trouble = null) else it }
            is GmEvent.PhoneResponding -> _state.update {
                if (it is BridgeState.Connected) it.copy(phoneResponding = e.responding) else it
            }
            is GmEvent.Alert -> when (e.type) {
                AlertType.BROWSER_INACTIVE, AlertType.BROWSER_INACTIVE_FROM_TIMEOUT, AlertType.BROWSER_INACTIVE_FROM_INACTIVITY ->
                    _state.update { if (it is BridgeState.Connected) it.copy(inactive = true) else it }
                AlertType.BROWSER_ACTIVE -> _state.update { if (it is BridgeState.Connected) it.copy(inactive = false) else it }
                else -> Unit
            }
            is GmEvent.SettingsUpdate -> if (e.settings.simCardsCount > 0) settings = e.settings
            is GmEvent.ConversationUpdate -> saveConversations(listOf(e.conversation))
            is GmEvent.MessageUpdate -> saveMessage(e.message, e.isOld)
            is GmEvent.Typing -> Unit
        }
    }

    private suspend fun saveConversations(received: List<Conversation>) {
        if (received.isEmpty()) return
        val before = received.associate { it.conversationID to gmDao.conversationById(it.conversationID) }
        // Read in Transmit, but the phone still says unread: an update from before it heard, or it never did.
        val stale = received.filter { GmMapping.isStaleUnread(it, readUpTo[GmMapping.threadId(it.conversationID)]) }.mapTo(HashSet()) { it.conversationID }
        val list = received.map { if (it.conversationID in stale) it.toBuilder().setUnread(false).build() else it }
        gmDao.upsertConversations(list.map(GmMapping::conversationEntity))
        contacts.addNames(list.flatMap(GmMapping::names))
        scope.launch { fetchPhotos(list) }
        for (id in stale) {
            val threadId = GmMapping.threadId(id)
            if (System.currentTimeMillis() - (phoneToldReadAt[threadId] ?: 0L) < PHONE_READ_RETRY_MS) continue
            val row = gmDao.conversation(threadId) ?: continue
            tellPhoneRead(row, newestMessage(threadId))
        }
        for (conv in list) {
            if (conv.unread) continue
            val threadId = GmMapping.threadId(conv.conversationID)
            if (before[conv.conversationID]?.unread == true || gmDao.unreadIncoming(threadId, 1).isNotEmpty()) {
                gmDao.markThreadRead(threadId)
                onReadElsewhere(threadId)
            }
        }
    }

    private suspend fun saveMessage(msg: GmMessage, isOld: Boolean) {
        if (msg.messageID.isEmpty() || msg.conversationID.isEmpty()) return
        if (GmMapping.isDeleted(msg)) {
            gmDao.deleteMessage(msg.messageID)
            return
        }
        if (GmMapping.isNotice(msg)) return
        val threadId = GmMapping.threadId(msg.conversationID)
        val placeholders = replacePlaceholder(msg)
        val existing = gmDao.message(msg.messageID)
        val outgoing = GmMapping.isOutgoing(msg)
        val read = existing?.read ?: (outgoing || isOld || isOnScreen(threadId))
        gmDao.upsertMessages(listOf(GmMapping.messageEntity(withKnownMedia(msg, existing, placeholders), read)))
        if (gmDao.conversation(threadId) == null) {
            client?.let { c -> runCatching { c.getConversation(msg.conversationID) }.getOrNull()?.let { saveConversations(listOf(it)) } }
        }
        if (existing == null && !outgoing && !isOld) onIncoming(threadId, Message.key(msg.messageID))
    }

    /**
     * The phone's copy of a message Transmit sent replaces the placeholder shown while sending.
     * Returns the placeholders it replaced.
     */
    private suspend fun replacePlaceholder(msg: GmMessage): List<GmMessage> {
        if (msg.tmpID.isEmpty()) return emptyList()
        val replaced = mutableListOf<GmMessage>()
        for (p in gmDao.messagesWithTmpId(msg.tmpID)) {
            if (p.messageId == msg.messageID) continue
            runCatching { GmMessage.parseFrom(p.proto) }.getOrNull()?.let(replaced::add)
            dao.copyTranslations(Message.key(p.messageId), Message.key(msg.messageID))
            gmDao.deleteMessage(p.messageId)
        }
        if (replaced.isNotEmpty()) {
            scope.launch {
                // By now every screen has reloaded and shows the phone's copy instead.
                delay(PLACEHOLDER_TRANSLATION_GRACE_MS)
                dropPlaceholderTranslations()
            }
        }
        return replaced
    }

    private suspend fun dropPlaceholderTranslations() =
        runCatching { dao.deleteOrphanPlaceholderTranslations(Message.key(PLACEHOLDER)) }.onFailure { Log.w(TAG, "Cleanup failed", it) }

    /**
     * [msg] keeping attachments the phone left out of it. Its copy of a photo Transmit sent often has
     * no file (the phone never re-uploads it), so the upload Transmit made stays in use; and a status
     * update can arrive without media an earlier copy had. Files already on this device are kept.
     */
    private fun withKnownMedia(msg: GmMessage, existing: GmMessageEntity?, placeholders: List<GmMessage>): GmMessage {
        val haveFile = { m: MediaContent -> cachedMediaFile(m.mediaID).exists() }
        var merged = msg
        for (p in placeholders) merged = GmMapping.keepMedia(merged, p, haveFile)
        existing?.let { runCatching { GmMessage.parseFrom(it.proto) }.getOrNull() }?.let { merged = GmMapping.keepMedia(merged, it, haveFile) }
        return merged
    }

    /** Remembers an upload for a placeholder, so its file is used when the phone's copy arrives. */
    suspend fun attachUpload(placeholderId: String, media: MediaContent, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            runCatching {
                val file = cachedMediaFile(media.mediaID)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }.onFailure { Log.w(TAG, "Couldn't cache upload", it) }
        }
        val row = gmDao.message(placeholderId) ?: return
        val msg = GmMessage.parseFrom(row.proto)
        val b = msg.toBuilder()
        val k = (0 until b.messageInfoCount).firstOrNull { b.getMessageInfo(it).hasMediaContent() } ?: return
        b.setMessageInfo(k, b.getMessageInfo(k).toBuilder().setMediaContent(media))
        gmDao.upsertMessages(listOf(GmMapping.messageEntity(b.build(), row.read)))
    }

    // ---- sync ------------------------------------------------------------------------------------

    private suspend fun initialSync() {
        val c = client ?: return
        dropPlaceholderTranslations()
        try {
            val inbox = c.listConversations(INITIAL_CONVERSATIONS)
            saveConversations(inbox.conversationsList)
            _synced.value = true
            runCatching { c.listConversations(ARCHIVED_CONVERSATIONS, ListConversationsRequest.Folder.ARCHIVE) }
                .onSuccess { saveConversations(it.conversationsList) }
            // Recent conversations get their latest messages ahead of time so they open instantly.
            for (conv in inbox.conversationsList.take(PREFETCH_CONVERSATIONS)) {
                if (gmDao.message(conv.latestMessageID) == null) fetchPage(conv, cursor = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Initial sync failed", e)
        }
    }

    /** Fetches the newest messages of a conversation once per connection (or again, with [force]). */
    fun refreshThread(threadId: Long, force: Boolean = false) {
        if (client == null || (!fetchedLatest.add(threadId) && !force)) return
        scope.launch {
            val conv = conversation(threadId) ?: return@launch
            runCatching { fetchPage(conv, cursor = null) }.onFailure {
                fetchedLatest.remove(threadId)
                Log.w(TAG, "Fetching messages failed", it)
            }
        }
    }

    /** Fetches messages older than the oldest cached one, unless the conversation's start is reached. */
    fun requestOlder(threadId: Long) {
        if (client == null || threadId in exhausted || !olderInFlight.add(threadId)) return
        scope.launch {
            try {
                val conv = conversation(threadId) ?: return@launch
                // Without anything cached there's nothing to page back from; refreshThread fetches the latest.
                val oldest = gmDao.messages(threadId, Int.MAX_VALUE).lastOrNull { !it.messageId.startsWith(PLACEHOLDER) } ?: return@launch
                val cursor = Cursor.newBuilder().setLastItemID(oldest.messageId).setLastItemTimestamp(oldest.timestamp).build()
                if (fetchPage(conv, cursor) == 0) exhausted += threadId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Fetching older messages failed", e)
            } finally {
                olderInFlight.remove(threadId)
            }
        }
    }

    /** Returns how many messages were new to the cache. */
    private suspend fun fetchPage(conv: Conversation, cursor: Cursor?): Int {
        val c = client ?: return 0
        val resp = c.fetchMessages(conv.conversationID, PAGE, cursor)
        val messages = resp.messagesList.filter { it.messageID.isNotEmpty() && !GmMapping.isNotice(it) && !GmMapping.isDeleted(it) }
        // In an unread conversation, what came in after the user's last reply is what's unread.
        val lastOutgoing = messages.filter(GmMapping::isOutgoing).maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE
        val placeholders = messages.associate { it.messageID to replacePlaceholder(it) }
        var added = 0
        val rows = messages.map { m ->
            val existing = gmDao.message(m.messageID)
            if (existing == null) added++
            val read = existing?.read ?: (GmMapping.isOutgoing(m) || !conv.unread || m.timestamp < lastOutgoing)
            GmMapping.messageEntity(withKnownMedia(m, existing, placeholders[m.messageID].orEmpty()), read)
        }
        gmDao.upsertMessages(rows)
        return added
    }

    suspend fun conversation(threadId: Long): Conversation? =
        gmDao.conversation(threadId)?.let { Conversation.parseFrom(it.proto) }

    /** The conversation with exactly [numbers]: a cached one, else one the phone finds or creates. */
    suspend fun threadFor(numbers: List<String>): Long {
        val wanted = numbers.map(PhoneNumbers::matchKey).toSet()
        for (row in gmDao.conversations()) {
            val conv = runCatching { Conversation.parseFrom(row.proto) }.getOrNull() ?: continue
            if (GmMapping.recipients(conv).map(PhoneNumbers::matchKey).toSet() == wanted) return row.threadId
        }
        val conv = requireClient().getOrCreateConversation(numbers)
        saveConversations(listOf(conv))
        return GmMapping.threadId(conv.conversationID)
    }

    /** Marks a conversation read in the cache, and on the phone when connected. */
    /** Per conversation, the newest message time (ms) read in Transmit; the phone's unread flag up to it is out of date. */
    private val readUpTo = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    /** When the phone was last told each conversation is read, so out-of-date updates don't ask it again and again. */
    private val phoneToldReadAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** The user has seen everything in [threadId]: clears it here and on the phone. Cheap when nothing is unread. */
    suspend fun markRead(threadId: Long) {
        val row = gmDao.conversation(threadId) ?: return
        val newest = newestMessage(threadId)
        readUpTo.merge(threadId, maxOf(row.lastMessageAt, newest?.timestamp ?: 0L), ::maxOf)
        val hadUnread = row.unread || gmDao.unreadIncoming(threadId, 1).isNotEmpty()
        if (!hadUnread) return
        gmDao.markThreadRead(threadId)
        gmDao.markConversationRead(threadId)
        tellPhoneRead(row, newest)
    }

    private suspend fun newestMessage(threadId: Long) = gmDao.messages(threadId, 10).firstOrNull { !it.messageId.startsWith(PLACEHOLDER) }

    private fun tellPhoneRead(row: GmConversationEntity, newest: GmMessageEntity?) {
        val c = client ?: return
        // The newest message: the cached one, unless the phone has a newer one not fetched yet.
        val messageId = newest?.takeIf { it.timestamp >= row.lastMessageAt }?.messageId
            ?: runCatching { Conversation.parseFrom(row.proto).latestMessageID }.getOrNull()?.ifEmpty { null }
            ?: newest?.messageId
            ?: return
        phoneToldReadAt[row.threadId] = System.currentTimeMillis()
        scope.launch { runCatching { c.markRead(row.conversationId, messageId) }.onFailure { Log.w(TAG, "markRead failed", it) } }
    }

    /** Deletes a message on the phone (which removes it everywhere) and from the cache. */
    suspend fun deleteMessage(messageId: String) {
        if (!messageId.startsWith(PLACEHOLDER)) {
            if (!requireClient().deleteMessage(messageId)) throw GmException("Google Messages couldn't delete that message")
        }
        gmDao.deleteMessage(messageId)
    }

    suspend fun deleteConversation(threadId: Long) {
        val row = gmDao.conversation(threadId) ?: return
        val conv = Conversation.parseFrom(row.proto)
        requireClient().deleteConversation(row.conversationId, GmMapping.recipients(conv).singleOrNull())
        gmDao.deleteThreadMessages(threadId)
        gmDao.deleteConversation(threadId)
    }

    suspend fun mediaInfo(messageId: String, index: Int): app.transmit.gm.proto.MediaContent? {
        val row = gmDao.message(messageId) ?: return null
        return GmMapping.mediaParts(GmMessage.parseFrom(row.proto)).getOrNull(index)
    }

    private val downloads = HashMap<String, Mutex>()

    private val fullSizeRequested = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * The decrypted file for a message's [index]th attachment, downloaded on first use: the full file,
     * else the thumbnail (and the phone is asked for the full one), else bytes sent inline.
     */
    suspend fun mediaFile(messageId: String, index: Int): Pair<File, String> {
        val row = gmDao.message(messageId) ?: throw java.io.FileNotFoundException(messageId)
        val msg = GmMessage.parseFrom(row.proto)
        val media = GmMapping.mediaParts(msg).getOrNull(index) ?: throw java.io.FileNotFoundException("$messageId/$index")
        val (id, key) = when {
            media.mediaID.isNotEmpty() -> media.mediaID to media.decryptionKey
            media.thumbnailMediaID.isNotEmpty() -> {
                requestFullSize(msg, index)
                media.thumbnailMediaID to media.thumbnailDecryptionKey
            }
            !media.mediaData.isEmpty -> "inline_${messageId}_$index" to null
            else -> {
                requestFullSize(msg, index)
                throw java.io.FileNotFoundException("$messageId/$index isn't on Google's servers yet")
            }
        }
        val file = cachedMediaFile(id)
        val lock = synchronized(downloads) { downloads.getOrPut(id) { Mutex() } }
        lock.withLock {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                val part = File(file.path + ".part")
                try {
                    if (key == null) {
                        part.writeBytes(media.mediaData.toByteArray())
                    } else {
                        val c = client ?: throw NotConnectedException()
                        part.outputStream().use { c.downloadMedia(id, key.toByteArray(), it) }
                    }
                    if (!part.renameTo(file)) throw java.io.IOException("Couldn't save ${file.name}")
                } finally {
                    part.delete()
                }
            }
        }
        return file to media.mimeType
    }

    /** Once per attachment: the phone uploads the full file and sends the message again with it. */
    private fun requestFullSize(msg: GmMessage, index: Int) {
        val c = client ?: return
        val actionId = msg.messageInfoList.filter { it.hasMediaContent() || (it.hasLocationContent() && it.locationContent.hasMediaContent()) }
            .getOrNull(index)?.actionMessageID?.takeIf { it.isNotEmpty() } ?: return
        if (msg.messageID.startsWith(PLACEHOLDER) || !fullSizeRequested.add("${msg.messageID}/$actionId")) return
        scope.launch {
            runCatching { c.requestFullSizeImage(msg.messageID, actionId) }
                .onFailure { Log.w(TAG, "Full-size request failed", it) }
        }
    }

    /** The SIM a conversation sends from, as the phone describes it. */
    fun simPayload(conv: Conversation): SIMPayload? {
        if (conv.hasSimCard() && conv.simCard.simData.hasSIMPayload()) return conv.simCard.simData.simPayload
        return settings?.simCardsList
            ?.firstOrNull { it.simParticipant.id == conv.defaultOutgoingID }
            ?.takeIf { it.simData.hasSIMPayload() }?.simData?.simPayload
    }

    /** When each participant's photo was last asked for, so conversation updates don't ask again. */
    private val photosAskedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val photoLock = Mutex()

    /** Asks the phone for the profile photos of the people in [convs] (RCS pictures, business logos). */
    private suspend fun fetchPhotos(convs: List<Conversation>) = photoLock.withLock {
        val now = System.currentTimeMillis()
        val due = convs.flatMap { it.participantsList }
            .filter { !it.isMe && it.id.participantID.isNotEmpty() && GmMapping.address(it).isNotEmpty() }
            .associate { it.id.participantID to GmMapping.address(it) }
            .filterKeys { now - (photosAskedAt[it] ?: 0L) > PHOTO_REFRESH_MS }
        for (batch in due.keys.chunked(PHOTO_BATCH)) {
            val c = client ?: return@withLock
            val photos = try {
                c.participantThumbnails(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Profile photos unavailable", e)
                return@withLock
            }
            batch.forEach { photosAskedAt[it] = now }
            withContext(Dispatchers.IO) { contacts.addPhotos(photos.mapNotNull { (id, bytes) -> due[id]?.let { it to bytes } }) }
        }
    }

    fun cachedMediaFile(mediaId: String) = File(mediaDir, mediaId.replace(Regex("[^\\w.-]"), "_"))

    companion object {
        private const val TAG = "GmBridge"
        private const val AUTH_KEY = "gm_auth"
        private const val QR_REFRESH_MS = 30_000L
        private const val QR_REFRESHES = 10
        private const val INITIAL_CONVERSATIONS = 100
        private const val ARCHIVED_CONVERSATIONS = 50
        private const val PREFETCH_CONVERSATIONS = 25
        private const val PAGE = 50
        private const val PLACEHOLDER_TRANSLATION_GRACE_MS = 30_000L
        private const val PHONE_READ_RETRY_MS = 60_000L
        private const val PHOTO_BATCH = 20
        private const val PHOTO_REFRESH_MS = 6 * 60 * 60_000L
        /** Message ids of sent messages the phone hasn't confirmed yet. */
        const val PLACEHOLDER = "tmp_"
    }
}
