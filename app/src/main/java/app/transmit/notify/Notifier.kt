package app.transmit.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.Telephony
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.transmit.MainActivity
import app.transmit.R
import app.transmit.ai.ChatPolicy
import app.transmit.ai.TranslationManager
import app.transmit.data.ContactsRepository
import app.transmit.data.NotificationContent
import app.transmit.data.SettingsRepository
import app.transmit.data.db.TransmitDao
import app.transmit.smart.Otp
import app.transmit.telephony.Message
import app.transmit.telephony.MessagingRepository
import app.transmit.telephony.Reactions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

class Notifier(
    private val context: Context,
    private val repo: MessagingRepository,
    private val contacts: ContactsRepository,
    private val dao: TransmitDao,
    private val settings: SettingsRepository,
    private val policy: ChatPolicy,
    private val translations: TranslationManager,
) {
    /** The conversation currently on screen; it gets no notifications. */
    @Volatile var activeThreadId: Long? = null

    private val nm get() = NotificationManagerCompat.from(context)

    fun createChannels() {
        nm.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_MESSAGES, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Messages").setDescription("Incoming messages").build(),
                NotificationChannelCompat.Builder(CHANNEL_FAILURES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Failed messages").setDescription("Messages that could not be sent").build(),
            ),
        )
    }

    private fun canPost() = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Posts (or silently refreshes, when [alert] is false) the notification for a conversation. */
    suspend fun showThread(threadId: Long, alert: Boolean) = withContext(Dispatchers.IO) {
        if (threadId == activeThreadId || !canPost()) return@withContext
        val unread = repo.unreadIncoming(threadId)
        if (unread.isEmpty()) {
            cancel(threadId)
            return@withContext
        }
        val s = settings.current()
        val eff = policy.effective(threadId, s)
        if (eff.muted) return@withContext
        // A reaction quotes its target in the original language, so it shows the target's translation.
        val reactionTargets = unread.mapNotNull { m ->
            val r = Reactions.parse(m.body)?.takeIf { it.quoted != null } ?: return@mapNotNull null
            repo.reactionTarget(threadId, m.date, r)?.let { m.key to it.key }
        }.toMap()
        val translated = dao.translations((unread.map { it.key } + reactionTargets.values).distinct(), eff.target.code)
            .associateBy { it.msgKey }
        val recipients = repo.recipientsForThread(threadId)
        val isGroup = recipients.size > 1

        val me = Person.Builder().setName("You").setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me).setGroupConversation(isGroup)
        val title = recipients.joinToString(", ") { contacts.lookup(it).displayName }
        if (isGroup) style.conversationTitle = title
        val people = HashMap<String, Person>()
        for (m in unread) {
            val address = m.sender ?: recipients.firstOrNull().orEmpty()
            val person = people.getOrPut(address) { person(address) }
            val translation = translated[reactionTargets[m.key] ?: m.key]?.text
            style.addMessage(notificationText(m, translation, s.notificationContent, eff.autoTranslate), m.date, person)
        }

        val shortcutId = pushShortcut(threadId, title, if (isGroup) null else people.values.firstOrNull())
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .setContentIntent(openThreadIntent(threadId))
            .setWhen(unread.last().date)
        // The newest verification code gets a one-tap copy, ahead of the other actions. On Android 16 the system
        // offers its own copy chip too; one button is enough, and ours also marks the code read.
        unread.asReversed().firstNotNullOfOrNull { Otp.find(it.body) }?.let {
            builder.addAction(copyCodeAction(threadId, it.code, showCode = s.notificationContent != NotificationContent.HIDDEN))
            builder.setAllowSystemGeneratedContextualActions(false)
        }
        builder
            .addAction(replyAction(threadId))
            .addAction(markReadAction(threadId))
        if (!alert) builder.setSilent(true)
        runCatching { nm.notify(notificationId(threadId), builder.build()) }
    }

    /** For a reaction, [translation] is that of the message it reacts to. */
    private fun notificationText(m: Message, translation: String?, mode: NotificationContent, auto: Boolean): String {
        Reactions.parse(m.body)?.let { r ->
            val quote = when (mode) {
                NotificationContent.HIDDEN -> return "New message"
                NotificationContent.ORIGINAL -> r.quoted
                NotificationContent.TRANSLATED -> r.quoted?.let { translation ?: it }
            }
            return "${r.emoji} ${quote?.let { "“${it.take(60)}”" } ?: "your photo"}"
        }
        val attachment = when {
            m.mmsPending -> "⬇ Multimedia message"
            m.attachments.any { it.isImage } -> "📷 Photo"
            m.attachments.any { it.isVideo } -> "🎬 Video"
            m.attachments.any { it.isAudio } -> "🎵 Audio"
            m.attachments.isNotEmpty() -> "📎 Attachment"
            else -> null
        }
        val text = when (mode) {
            NotificationContent.HIDDEN -> return "New message"
            NotificationContent.ORIGINAL -> m.body
            NotificationContent.TRANSLATED -> when {
                translation != null -> translation
                auto && translations.isAutoTranslatable(m) -> "🌐 …"
                else -> m.body
            }
        }
        return listOfNotNull(attachment, text.takeIf { it.isNotBlank() }).joinToString(" ").ifBlank { "New message" }
    }

    fun cancel(threadId: Long) {
        nm.cancel(notificationId(threadId))
    }

    suspend fun notifySendFailed(messageUri: Uri) = withContext(Dispatchers.IO) {
        if (!canPost()) return@withContext
        val column = if (messageUri.authority == "mms") Telephony.Mms.THREAD_ID else Telephony.Sms.THREAD_ID
        val threadId = context.contentResolver.query(messageUri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        } ?: return@withContext
        val name = repo.recipientsForThread(threadId).joinToString(", ") { contacts.lookup(it).displayName }
        val n = NotificationCompat.Builder(context, CHANNEL_FAILURES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("Message not sent")
            .setContentText("To $name · tap to retry")
            .setContentIntent(openThreadIntent(threadId))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(notificationId(threadId) + 1, n) }
    }

    private fun person(address: String): Person {
        val info = contacts.lookup(address)
        val bitmap = info.photoUri?.let { uri ->
            runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        return Person.Builder()
            .setName(info.displayName)
            .setKey(address)
            .setIcon(Avatars.icon(info.displayName, address, bitmap))
            .apply { info.lookupUri?.let { setUri(it.toString()) } }
            .build()
    }

    private fun pushShortcut(threadId: Long, title: String, person: Person?): String {
        val id = "thread_$threadId"
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(title.ifBlank { "Conversation" })
                .setLongLived(true)
                .setLocusId(LocusIdCompat(id))
                .setIntent(MainActivity.threadIntent(context, threadId))
                .apply {
                    if (person != null) {
                        setPerson(person)
                        person.icon?.let { setIcon(it) }
                    } else {
                        setIcon(Avatars.icon(title, title, null))
                    }
                }
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
        return id
    }

    private fun openThreadIntent(threadId: Long): PendingIntent = PendingIntent.getActivity(
        context, notificationId(threadId), MainActivity.threadIntent(context, threadId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun replyAction(threadId: Long): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_REPLY)
            .putExtra(NotificationActionReceiver.EXTRA_THREAD, threadId)
        val pi = PendingIntent.getBroadcast(context, notificationId(threadId), intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val input = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel("Reply").build()
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_message, "Reply", pi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun copyCodeAction(threadId: Long, code: String, showCode: Boolean): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_COPY_CODE)
            .putExtra(NotificationActionReceiver.EXTRA_THREAD, threadId)
            .putExtra(NotificationActionReceiver.EXTRA_CODE, code)
        val pi = PendingIntent.getBroadcast(context, notificationId(threadId), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_message, if (showCode && code.length <= 10) "Copy $code" else "Copy code", pi)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(threadId: Long): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_MARK_READ)
            .putExtra(NotificationActionReceiver.EXTRA_THREAD, threadId)
        val pi = PendingIntent.getBroadcast(context, notificationId(threadId), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_message, "Mark as read", pi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_FAILURES = "failures"
        fun notificationId(threadId: Long) = (threadId % 1_000_000).toInt() * 2
    }
}

/** Avatar icons for notification people and conversation shortcuts (Compose draws its own). */
object Avatars {
    val palette = intArrayOf(
        0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF6C00.toInt(), 0xFFAB47BC.toInt(),
        0xFF42A5F5.toInt(), 0xFFEC407A.toInt(), 0xFF7CB342.toInt(), 0xFF8D6E63.toInt(),
    )

    fun colorFor(seed: String): Int {
        // Mix the bits: hashCode() mod 8 of similar phone numbers collides constantly (31 ≡ -1 mod 8).
        var h = seed.hashCode()
        h = (h xor (h ushr 16)) * 0x45d9f3b
        h = h xor (h ushr 16)
        return palette[Math.floorMod(h, palette.size)]
    }

    private const val SIZE = 192

    /** Adaptive icon: the contact photo center-cropped, or a full-bleed colour with an initial. */
    fun icon(name: String, seed: String, photo: Bitmap?): IconCompat {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (photo != null) {
            val side = minOf(photo.width, photo.height)
            val src = android.graphics.Rect((photo.width - side) / 2, (photo.height - side) / 2, (photo.width + side) / 2, (photo.height + side) / 2)
            canvas.drawBitmap(photo, src, android.graphics.Rect(0, 0, SIZE, SIZE), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            canvas.drawColor(colorFor(seed))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = SIZE * 0.3f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val letter = name.firstOrNull { it.isLetter() }?.uppercase() ?: "#"
            canvas.drawText(letter, SIZE / 2f, SIZE / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        }
        return IconCompat.createWithAdaptiveBitmap(out)
    }
}
