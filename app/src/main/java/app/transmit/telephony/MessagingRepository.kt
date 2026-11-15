package app.transmit.telephony

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import app.transmit.telephony.pdu.MmsAddrType
import app.transmit.telephony.pdu.PduText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Read/write access to the platform SMS/MMS store (the source of truth for all messages). */
class MessagingRepository(private val context: Context, private val subscriptions: Subscriptions) {
    private val resolver get() = context.contentResolver

    private data class Latest(val date: Long, val key: String, val text: String, val outgoing: Boolean, val media: Boolean)
    private val latestCache = ConcurrentHashMap<Long, Latest>()
    /** MMS addresses by message id, with the message date to catch a reused id. Read once: a query per message adds up. */
    private val addressCache = ConcurrentHashMap<Long, Pair<Long, List<Pair<String, Int>>>>()

    fun canRead(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Emits whenever anything in the SMS/MMS store changes. */
    val changes: Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val uris = listOf(Uri.parse("content://mms-sms/"), Telephony.Sms.CONTENT_URI, Telephony.Mms.CONTENT_URI)
        uris.forEach { runCatching { resolver.registerContentObserver(it, true, observer) } }
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    // ---- conversations -------------------------------------------------------------------------

    /** Conversations, newest first. With [limit], only the newest few: a quick first screenful. */
    suspend fun conversations(limit: Int = Int.MAX_VALUE): List<Conversation> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext emptyList()
        val addresses = canonicalAddresses()
        data class ThreadRow(
            val threadId: Long, val recipients: List<String>, val date: Long, val count: Int,
            val snippet: String, val read: Boolean, val hasAttachment: Boolean,
        )
        val threads = mutableListOf<ThreadRow>()
        val uri = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
        val projection = arrayOf(
            Telephony.Threads._ID, Telephony.Threads.DATE, Telephony.Threads.MESSAGE_COUNT,
            Telephony.Threads.RECIPIENT_IDS, Telephony.Threads.SNIPPET, Telephony.Threads.SNIPPET_CHARSET,
            Telephony.Threads.READ, Telephony.Threads.HAS_ATTACHMENT,
        )
        // The provider ignores LIMIT here, so stop reading instead.
        safeQuery(uri, projection, null, null, "${Telephony.Threads.DATE} DESC")?.use { c ->
            while (threads.size < limit && c.moveToNext()) {
                val recipients = c.str(Telephony.Threads.RECIPIENT_IDS).orEmpty()
                    .split(' ').mapNotNull { it.trim().toLongOrNull()?.let(addresses::get) }
                if (recipients.isEmpty()) continue
                threads += ThreadRow(
                    threadId = c.long(Telephony.Threads._ID), recipients = recipients, date = c.long(Telephony.Threads.DATE),
                    count = c.int(Telephony.Threads.MESSAGE_COUNT),
                    snippet = decodeSnippet(c.str(Telephony.Threads.SNIPPET), c.int(Telephony.Threads.SNIPPET_CHARSET)),
                    read = c.int(Telephony.Threads.READ) != 0, hasAttachment = c.int(Telephony.Threads.HAS_ATTACHMENT) == 1,
                )
            }
        }
        if (threads.isEmpty()) return@withContext emptyList()
        val bulk = latestMessages(threads.map { it.threadId }.takeIf { limit != Int.MAX_VALUE })
        threads.mapNotNull { t ->
            val latest = if (bulk != null) bulk[t.threadId] else latestMessage(t.threadId, t.date)
            if (t.count == 0 && latest == null) return@mapNotNull null
            Conversation(
                threadId = t.threadId,
                recipients = t.recipients,
                snippet = latest?.text ?: t.snippet,
                snippetKey = latest?.key,
                snippetOutgoing = latest?.outgoing ?: false,
                date = latest?.date ?: t.date,
                unread = !t.read,
                messageCount = t.count,
                hasAttachment = t.hasAttachment || latest?.media == true,
            )
        }.sortedByDescending { it.date }
    }

    private fun decodeSnippet(snippet: String?, charset: Int): String {
        if (snippet.isNullOrEmpty()) return ""
        // MMS subjects are stored as ISO-8859-1 strings of the raw bytes.
        return if (charset == 106) String(snippet.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) else snippet
    }

    /**
     * The newest message of every thread (or of [threadIds]) from one provider query, plus one for
     * the MMS text parts, instead of two to four queries per thread. Null if the provider doesn't
     * support the query.
     */
    private fun latestMessages(threadIds: List<Long>?): Map<Long, Latest>? {
        data class Row(val threadId: Long, val date: Long, val id: Long, val mms: Boolean, val body: String, val outgoing: Boolean, val pending: Boolean)
        // The provider's union exposes MMS dates in seconds as "date"; "normalized_date" is milliseconds for both.
        // Android 16 leaves out "transport_type", so MMS rows are told apart by their message type.
        val projection = arrayOf(
            Telephony.Sms.THREAD_ID, "normalized_date", Telephony.Sms._ID,
            Telephony.Sms.BODY, Telephony.Sms.TYPE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_TYPE,
        )
        val rows = HashMap<Long, Row>()
        val selection = threadIds?.let { "${Telephony.Sms.THREAD_ID} IN (${it.joinToString(",")})" }
        val cursor = safeQuery(Telephony.MmsSms.CONTENT_CONVERSATIONS_URI, projection, selection, null, null) ?: return null
        cursor.use { c ->
            if (c.getColumnIndex("normalized_date") < 0) return null
            while (c.moveToNext()) {
                val mms = c.int(Telephony.Mms.MESSAGE_TYPE) != 0
                val row = Row(
                    threadId = c.long(Telephony.Sms.THREAD_ID), date = c.long("normalized_date"), id = c.long(Telephony.Sms._ID), mms = mms,
                    body = c.str(Telephony.Sms.BODY).orEmpty(),
                    outgoing = if (mms) c.int(Telephony.Mms.MESSAGE_BOX) != Telephony.Mms.MESSAGE_BOX_INBOX else c.int(Telephony.Sms.TYPE) != Telephony.Sms.MESSAGE_TYPE_INBOX,
                    pending = mms && c.int(Telephony.Mms.MESSAGE_TYPE) == 130,
                )
                // Messages with the same timestamp can both come back; keep the later one.
                val prev = rows[row.threadId]
                if (prev == null || row.date > prev.date || (row.date == prev.date && row.id > prev.id)) rows[row.threadId] = row
            }
        }
        val parts = mmsParts(rows.values.filter { it.mms }.map { it.id })
        return rows.mapValues { (_, r) ->
            val p = if (r.mms) parts[r.id].orEmpty() else emptyList()
            val body = if (r.mms) p.filter { it.contentType.equals("text/plain", true) }.joinToString("\n") { it.text.orEmpty() }.trim() else r.body
            val media = p.map { it.contentType.lowercase() }.filter { it != "text/plain" && it != "application/smil" }
            Latest(r.date, if (r.mms) Message.mmsKey(r.id) else Message.smsKey(r.id), snippetText(body, media, r.pending), r.outgoing, media.isNotEmpty())
        }
    }

    private fun latestMessage(threadId: Long, threadDate: Long): Latest? {
        latestCache[threadId]?.let { if (it.date >= threadDate - 1000 && threadDate > 0) return it }
        val msgs = loadMessages(threadId, limit = 1, beforeMs = null, includeReactions = true)
        val m = msgs.firstOrNull() ?: return null
        val text = snippetText(m.body, m.attachments.map { it.mimeType }, m.mmsPending)
        return Latest(m.date, m.key, text, m.outgoing, m.attachments.isNotEmpty()).also { latestCache[threadId] = it }
    }

    private fun snippetText(body: String, media: List<String>, pending: Boolean): String = when {
        body.isNotBlank() -> body
        media.any { it.startsWith("image/") } -> "📷"
        media.any { it.startsWith("video/") } -> "🎬"
        media.any { it.startsWith("audio/") } -> "🎵"
        media.isNotEmpty() -> "📎"
        pending -> "⬇"
        else -> ""
    }

    fun canonicalAddresses(): Map<Long, String> {
        val map = HashMap<Long, String>()
        safeQuery(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) map[c.getLong(0)] = c.getString(1) ?: continue
        }
        return map
    }

    suspend fun recipientsForThread(threadId: Long): List<String> = withContext(Dispatchers.IO) {
        val addresses = canonicalAddresses()
        val uri = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
        safeQuery(uri, arrayOf(Telephony.Threads.RECIPIENT_IDS), "${Telephony.Threads._ID}=?", arrayOf(threadId.toString()), null)?.use { c ->
            if (c.moveToFirst()) {
                return@withContext c.getString(0).orEmpty().split(' ').mapNotNull { it.trim().toLongOrNull()?.let(addresses::get) }
            }
        }
        emptyList()
    }

    fun threadIdFor(recipients: Collection<String>): Long =
        Telephony.Threads.getOrCreateThreadId(context, recipients.toSet())

    // ---- messages ------------------------------------------------------------------------------

    suspend fun messages(threadId: Long, limit: Int, beforeMs: Long? = null): List<Message> =
        withContext(Dispatchers.IO) { loadMessages(threadId, limit, beforeMs, includeReactions = true) }

    suspend fun message(key: String): Message? = withContext(Dispatchers.IO) {
        val id = key.drop(1).toLongOrNull() ?: return@withContext null
        when (key.first()) {
            's' -> querySms("${Telephony.Sms._ID}=?", arrayOf(id.toString()), null).firstOrNull()
            'm' -> queryMms("${Telephony.Mms._ID}=?", arrayOf(id.toString()), null).firstOrNull()
            else -> null
        }
    }

    /**
     * The message that reaction [r], received or sent at [reactionDate] in [threadId], refers to:
     * the newest earlier message it quotes. Null when that isn't among the last [REACTION_WINDOW].
     */
    suspend fun reactionTarget(threadId: Long, reactionDate: Long, r: Reactions.Parsed): Message? = withContext(Dispatchers.IO) {
        Reactions.target(r, loadMessages(threadId, REACTION_WINDOW, beforeMs = reactionDate, includeReactions = true))
    }

    suspend fun unreadIncoming(threadId: Long, limit: Int = 8): List<Message> = withContext(Dispatchers.IO) {
        val sms = querySms(
            "${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.READ}=0 AND ${Telephony.Sms.TYPE}=${Telephony.Sms.MESSAGE_TYPE_INBOX}",
            arrayOf(threadId.toString()), "${Telephony.Sms.DATE} DESC LIMIT $limit",
        )
        val mms = queryMms(
            "${Telephony.Mms.THREAD_ID}=? AND ${Telephony.Mms.READ}=0 AND ${Telephony.Mms.MESSAGE_BOX}=${Telephony.Mms.MESSAGE_BOX_INBOX}",
            arrayOf(threadId.toString()), "${Telephony.Mms.DATE} DESC LIMIT $limit",
        )
        (sms + mms).sortedBy { it.date }.takeLast(limit)
    }

    suspend fun unreadThreadIds(): Set<Long> = withContext(Dispatchers.IO) {
        val ids = mutableSetOf<Long>()
        safeQuery(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID), "${Telephony.Sms.READ}=0 AND ${Telephony.Sms.TYPE}=1", null, null)?.use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        safeQuery(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms.THREAD_ID), "${Telephony.Mms.READ}=0 AND ${Telephony.Mms.MESSAGE_BOX}=1", null, null)?.use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        ids
    }

    private fun loadMessages(threadId: Long, limit: Int, beforeMs: Long?, includeReactions: Boolean): List<Message> {
        val smsSel = buildString {
            append("${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.TYPE}!=${Telephony.Sms.MESSAGE_TYPE_DRAFT}")
            if (beforeMs != null) append(" AND ${Telephony.Sms.DATE}<$beforeMs")
        }
        val mmsSel = buildString {
            append("${Telephony.Mms.THREAD_ID}=? AND ${Telephony.Mms.MESSAGE_BOX}!=${Telephony.Mms.MESSAGE_BOX_DRAFTS}")
            if (beforeMs != null) append(" AND ${Telephony.Mms.DATE}<${beforeMs / 1000}")
        }
        val args = arrayOf(threadId.toString())
        val sms = querySms(smsSel, args, "${Telephony.Sms.DATE} DESC LIMIT $limit")
        val mms = queryMms(mmsSel, args, "${Telephony.Mms.DATE} DESC LIMIT $limit")
        return (sms + mms).sortedWith(compareByDescending<Message> { it.date }.thenByDescending { it.id }).take(limit)
    }

    private fun querySms(selection: String, args: Array<String>?, order: String?): List<Message> {
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.READ,
            Telephony.Sms.STATUS, Telephony.Sms.SUBSCRIPTION_ID, Telephony.Sms.ERROR_CODE,
        )
        val out = mutableListOf<Message>()
        safeQuery(Telephony.Sms.CONTENT_URI, projection, selection, args, order)?.use { c ->
            while (c.moveToNext()) {
                val type = c.int(Telephony.Sms.TYPE)
                val outgoing = type != Telephony.Sms.MESSAGE_TYPE_INBOX
                val smsStatus = c.int(Telephony.Sms.STATUS)
                val status = when (type) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> DeliveryStatus.RECEIVED
                    Telephony.Sms.MESSAGE_TYPE_SENT -> when {
                        smsStatus == Telephony.Sms.STATUS_COMPLETE -> DeliveryStatus.DELIVERED
                        smsStatus == Telephony.Sms.STATUS_FAILED -> DeliveryStatus.FAILED
                        else -> DeliveryStatus.SENT
                    }
                    Telephony.Sms.MESSAGE_TYPE_FAILED -> DeliveryStatus.FAILED
                    else -> DeliveryStatus.SENDING
                }
                val id = c.long(Telephony.Sms._ID)
                val address = c.str(Telephony.Sms.ADDRESS).orEmpty()
                out += Message(
                    key = Message.smsKey(id), id = id, kind = MessageKind.SMS,
                    threadId = c.long(Telephony.Sms.THREAD_ID),
                    sender = if (outgoing) null else address,
                    recipients = if (outgoing) listOf(address) else emptyList(),
                    body = c.str(Telephony.Sms.BODY).orEmpty(),
                    date = c.long(Telephony.Sms.DATE),
                    dateSent = c.long(Telephony.Sms.DATE_SENT),
                    outgoing = outgoing, status = status,
                    read = c.int(Telephony.Sms.READ) == 1,
                    subId = c.int(Telephony.Sms.SUBSCRIPTION_ID),
                    errorCode = c.int(Telephony.Sms.ERROR_CODE),
                )
            }
        }
        return out
    }

    private fun queryMms(selection: String, args: Array<String>?, order: String?): List<Message> {
        val projection = arrayOf(
            Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.DATE_SENT,
            Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ, Telephony.Mms.MESSAGE_TYPE, Telephony.Mms.SUBJECT,
            Telephony.Mms.SUBJECT_CHARSET, Telephony.Mms.CONTENT_LOCATION, Telephony.Mms.EXPIRY,
            Telephony.Mms.MESSAGE_SIZE, Telephony.Mms.SUBSCRIPTION_ID, Telephony.Mms.STATUS,
        )
        data class Row(
            val id: Long, val threadId: Long, val date: Long, val dateSent: Long, val box: Int, val read: Boolean,
            val mType: Int, val subject: String?, val ctl: String?, val exp: Long, val size: Long, val subId: Int,
        )
        val rows = mutableListOf<Row>()
        safeQuery(Telephony.Mms.CONTENT_URI, projection, selection, args, order)?.use { c ->
            while (c.moveToNext()) {
                rows += Row(
                    id = c.long(Telephony.Mms._ID), threadId = c.long(Telephony.Mms.THREAD_ID),
                    date = c.long(Telephony.Mms.DATE) * 1000, dateSent = c.long(Telephony.Mms.DATE_SENT) * 1000,
                    box = c.int(Telephony.Mms.MESSAGE_BOX), read = c.int(Telephony.Mms.READ) == 1,
                    mType = c.int(Telephony.Mms.MESSAGE_TYPE),
                    subject = decodeSnippet(c.str(Telephony.Mms.SUBJECT), c.int(Telephony.Mms.SUBJECT_CHARSET)).ifBlank { null },
                    ctl = c.str(Telephony.Mms.CONTENT_LOCATION), exp = c.long(Telephony.Mms.EXPIRY),
                    size = c.long(Telephony.Mms.MESSAGE_SIZE), subId = c.int(Telephony.Mms.SUBSCRIPTION_ID),
                )
            }
        }
        if (rows.isEmpty()) return emptyList()
        val parts = mmsParts(rows.map { it.id })
        val sole = soleRecipients(rows.mapTo(HashSet()) { it.threadId })
        val own = subscriptions.ownNumbers()
        return rows.map { r ->
            val p = parts[r.id].orEmpty()
            val text = p.filter { it.contentType.equals("text/plain", true) }.joinToString("\n") { it.text.orEmpty() }.trim()
            val attachments = p.filter { !it.contentType.equals("text/plain", true) && !it.contentType.equals("application/smil", true) }
                .map { Attachment(it.id, Uri.parse("content://mms/part/${it.id}"), it.contentType.lowercase(), it.name) }
            val outgoing = r.box != Telephony.Mms.MESSAGE_BOX_INBOX
            // In a one-to-one thread the other person is the sender or the recipient, so there's nothing to look up.
            val addrs = sole[r.threadId]?.let { listOf(it to if (outgoing) MmsAddrType.TO else MmsAddrType.FROM) }
                ?: mmsAddresses(r.id, r.date, cache = r.mType != 130)
            val from = addrs.firstOrNull { it.second == MmsAddrType.FROM }?.first
            val recipients = addrs.filter { it.second == MmsAddrType.TO || it.second == MmsAddrType.CC }
                .map { it.first }.filter { a -> a != "insert-address-token" && own.none { PhoneNumbers.same(it, a) } }
            val status = when (r.box) {
                Telephony.Mms.MESSAGE_BOX_INBOX -> DeliveryStatus.RECEIVED
                Telephony.Mms.MESSAGE_BOX_SENT -> DeliveryStatus.SENT
                Telephony.Mms.MESSAGE_BOX_FAILED -> DeliveryStatus.FAILED
                else -> DeliveryStatus.SENDING
            }
            Message(
                key = Message.mmsKey(r.id), id = r.id, kind = MessageKind.MMS, threadId = r.threadId,
                sender = if (outgoing) null else from,
                recipients = recipients,
                body = text, date = r.date, dateSent = r.dateSent, outgoing = outgoing, status = status,
                read = r.read, subId = r.subId, attachments = attachments, subject = r.subject,
                mmsPending = r.mType == 130, mmsContentLocation = r.ctl, mmsExpiry = r.exp,
                sizeBytes = if (r.size > 0) r.size else p.sumOf { it.size },
            )
        }
    }

    private data class PartRow(val id: Long, val mid: Long, val contentType: String, val name: String?, val text: String?, val size: Long)

    private fun mmsParts(ids: List<Long>): Map<Long, List<PartRow>> {
        val out = HashMap<Long, MutableList<PartRow>>()
        ids.chunked(200).forEach { chunk ->
            val sel = "${Telephony.Mms.Part.MSG_ID} IN (${chunk.joinToString(",")})"
            val projection = arrayOf(
                Telephony.Mms.Part._ID, Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.CONTENT_TYPE,
                Telephony.Mms.Part.NAME, Telephony.Mms.Part.FILENAME, Telephony.Mms.Part.CONTENT_LOCATION,
                Telephony.Mms.Part.TEXT,
            )
            safeQuery(Uri.parse("content://mms/part"), projection, sel, null, "${Telephony.Mms.Part.SEQ} ASC, ${Telephony.Mms.Part._ID} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val row = PartRow(
                        id = c.long(Telephony.Mms.Part._ID), mid = c.long(Telephony.Mms.Part.MSG_ID),
                        contentType = c.str(Telephony.Mms.Part.CONTENT_TYPE).orEmpty(),
                        name = c.str(Telephony.Mms.Part.NAME) ?: c.str(Telephony.Mms.Part.FILENAME) ?: c.str(Telephony.Mms.Part.CONTENT_LOCATION),
                        text = c.str(Telephony.Mms.Part.TEXT),
                        size = c.str(Telephony.Mms.Part.TEXT)?.length?.toLong() ?: 0,
                    )
                    out.getOrPut(row.mid) { mutableListOf() } += row
                }
            }
        }
        return out
    }

    /** The other person in each one-to-one thread among [threadIds]; group threads are left out. */
    private fun soleRecipients(threadIds: Set<Long>): Map<Long, String> {
        val recipientIds = HashMap<Long, Long>()
        val uri = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
        safeQuery(uri, arrayOf(Telephony.Threads._ID, Telephony.Threads.RECIPIENT_IDS), "${Telephony.Threads._ID} IN (${threadIds.joinToString(",")})", null, null)?.use { c ->
            while (c.moveToNext()) c.getString(1)?.trim()?.toLongOrNull()?.let { recipientIds[c.getLong(0)] = it }
        }
        return recipientIds.mapNotNull { (thread, id) ->
            safeQuery(Uri.parse("content://mms-sms/canonical-address/$id"), null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { thread to it } else null
            }
        }.toMap()
    }

    /** A notification for an undownloaded MMS ([cache] false) gains its addresses on download, so it isn't cached. */
    private fun mmsAddresses(id: Long, date: Long, cache: Boolean): List<Pair<String, Int>> {
        addressCache[id]?.let { (d, addrs) -> if (d == date) return addrs }
        val out = mutableListOf<Pair<String, Int>>()
        val read = safeQuery(Uri.parse("content://mms/$id/addr"), arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                // Some apps store the PDU form, "+15551234567/TYPE=PLMN".
                val a = c.getString(0)?.let(PduText::stripAddressType) ?: continue
                out += a to c.getInt(1)
            }
        } != null
        if (cache && read) addressCache[id] = date to out
        return out
    }

    // ---- writes (require the default SMS role) ---------------------------------------------------

    suspend fun markThreadRead(threadId: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        val args = arrayOf(threadId.toString())
        runCatching { resolver.update(Telephony.Sms.CONTENT_URI, values, "${Telephony.Sms.THREAD_ID}=? AND (${Telephony.Sms.READ}=0 OR ${Telephony.Sms.SEEN}=0)", args) }
        runCatching { resolver.update(Telephony.Mms.CONTENT_URI, values, "${Telephony.Mms.THREAD_ID}=? AND (${Telephony.Mms.READ}=0 OR ${Telephony.Mms.SEEN}=0)", args) }
        Unit
    }

    suspend fun deleteMessage(message: Message): Unit = withContext(Dispatchers.IO) {
        val uri = when (message.kind) {
            MessageKind.SMS -> Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, message.id.toString())
            MessageKind.MMS -> Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, message.id.toString())
        }
        runCatching { resolver.delete(uri, null, null) }.onFailure { Log.w(TAG, "delete failed", it) }
        latestCache.remove(message.threadId); Unit
    }

    suspend fun deleteThread(threadId: Long): Unit = withContext(Dispatchers.IO) {
        runCatching { resolver.delete(Uri.withAppendedPath(Telephony.Threads.CONTENT_URI, threadId.toString()), null, null) }
            .onFailure { Log.w(TAG, "delete thread failed", it) }
        latestCache.remove(threadId); Unit
    }

    fun invalidate(threadId: Long) {
        latestCache.remove(threadId)
    }

    private fun safeQuery(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor? =
        try {
            resolver.query(uri, projection, selection, args, order)
        } catch (e: Exception) {
            Log.w(TAG, "query failed: $uri", e)
            null
        }

    companion object {
        private const val TAG = "MessagingRepo"
        /** How far back a reaction's target is looked for; reactions are nearly always to recent messages. */
        private const val REACTION_WINDOW = 50
    }
}

internal fun Cursor.long(col: String): Long = getColumnIndex(col).let { if (it < 0 || isNull(it)) 0L else getLong(it) }
internal fun Cursor.int(col: String): Int = getColumnIndex(col).let { if (it < 0 || isNull(it)) 0 else getInt(it) }
internal fun Cursor.str(col: String): String? = getColumnIndex(col).let { if (it < 0 || isNull(it)) null else getString(it) }
