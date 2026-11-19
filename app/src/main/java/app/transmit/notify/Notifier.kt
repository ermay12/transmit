package app.transmit.notify

import androidx.annotation.StringRes
import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import app.transmit.MainActivity
import app.transmit.R
import app.transmit.access.EndReason
import app.transmit.ui.access.AccessText
import app.transmit.ai.ChatPolicy
import app.transmit.ai.TranslationManager
import app.transmit.data.ContactsRepository
import app.transmit.data.NotificationContent
import app.transmit.data.SettingsRepository
import app.transmit.data.chatTitle
import app.transmit.data.db.TransmitDao
import app.transmit.data.db.shown
import app.transmit.data.db.shownWithReading
import app.transmit.smart.Otp
import app.transmit.messaging.Message
import app.transmit.messaging.MessagingRepository
import app.transmit.messaging.Reactions
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
    private val shortcuts: ConversationShortcuts,
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
                NotificationChannelCompat.Builder(CHANNEL_CONNECTION, NotificationManagerCompat.IMPORTANCE_MIN)
                    .setName("Connection").setDescription("Shown while Transmit stays connected to Google Messages")
                    .setShowBadge(false).build(),
                NotificationChannelCompat.Builder(CHANNEL_PAIRING, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Pairing").setDescription("When Transmit needs to be paired with Google Messages again").build(),
                NotificationChannelCompat.Builder(CHANNEL_PLAN, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.notify_plan_channel))
                    .setDescription(context.getString(R.string.notify_plan_channel_description)).build(),
            ),
        )
        // A channel's badge setting can't change once created, so the old badging one is replaced.
        runCatching { nm.deleteNotificationChannel(OLD_CHANNEL_CONNECTION) } // refused while a service still runs on it
    }

    /** Before Android 13 there is no notification permission to hold (checking it always says denied). */
    private fun canPost() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

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
        val customName = eff.overrides?.name
        // The system titles conversation notifications with the shortcut's label, so a custom name shows for 1:1 chats too.
        val title = customName ?: recipients.joinToString(", ") { contacts.lookup(it).displayName }
        if (isGroup || customName != null) style.conversationTitle = title
        val people = HashMap<String, Person>()
        for (m in unread) {
            val address = m.sender ?: recipients.firstOrNull().orEmpty()
            val person = people.getOrPut(address) { shortcuts.person(address) }
            val display = s.scriptDisplay(eff.target)
            // A reaction quotes in one line; a message gets its pinyin or romaji under it.
            val translation = reactionTargets[m.key]?.let { translated[it]?.shown(display) } ?: translated[m.key]?.shownWithReading(display)
            style.addMessage(notificationText(m, translation, s.notificationContent, eff.autoTranslate), m.date, person)
        }

        // Labelled like the conversation list, so the share sheet and launcher show the same name.
        val label = chatTitle(recipients.map(contacts::lookup), customName)
        val shortcutId = shortcuts.push(threadId, label, if (isGroup) null else people.values.firstOrNull(), recipients.firstOrNull() ?: title)
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
                // Only while a translation is on its way: a failed one, or none coming, shows the original.
                auto && translations.expectsAutoTranslation(m) -> "🌐 …"
                else -> m.body
            }
        }
        return listOfNotNull(attachment, text.takeIf { it.isNotBlank() }).joinToString(" ").ifBlank { "New message" }
    }

    fun cancel(threadId: Long) {
        nm.cancel(notificationId(threadId))
    }

    suspend fun notifySendFailed(threadId: Long) = withContext(Dispatchers.IO) {
        if (!canPost() || threadId == activeThreadId) return@withContext
        val name = dao.chatSettings(threadId)?.name
            ?: repo.recipientsForThread(threadId).joinToString(", ") { contacts.lookup(it).displayName }
        val n = NotificationCompat.Builder(context, CHANNEL_FAILURES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("Message not sent")
            .setContentText("To $name · tap to retry")
            .setContentIntent(openThreadIntent(threadId))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(notificationId(threadId) + 1, n) }
    }

    /** The ongoing notification the connection service runs under. */
    fun connectionNotification(text: String): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("Connected to Google Messages")
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    fun notifyLoggedOut(reason: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("Transmit is disconnected")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(LOGGED_OUT_ID, n) }
    }

    // ---- plan ------------------------------------------------------------------------------------

    /** Day 6 of the free week. */
    fun notifyTrialEndsTomorrow() = notifyPlan(R.string.notify_trial_ends_tomorrow, R.string.access_keep_going)

    /** The trial or subscription ended (or the trial's AI ran out) while the app wasn't open. */
    fun notifyAccessEnded(reason: EndReason) = notifyPlan(AccessText.ended(reason), R.string.access_keep_going)

    /** Another device took the subscription while the app wasn't open. */
    fun notifyInUseElsewhere() = notifyPlan(R.string.access_in_use_elsewhere, R.string.notify_in_use_elsewhere_body)

    /** The plans screen is showing: its notification has done its job. */
    fun cancelPlan() = nm.cancel(PLAN_ID)

    private fun notifyPlan(@StringRes titleRes: Int, @StringRes textRes: Int) {
        if (!canPost()) return
        val title = context.getString(titleRes)
        val text = context.getString(textRes)
        val n = NotificationCompat.Builder(context, CHANNEL_PLAN)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(context, PLAN_ID, MainActivity.plansIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(PLAN_ID, n) }
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
        const val CHANNEL_CONNECTION = "connection_quiet"
        private const val OLD_CHANNEL_CONNECTION = "connection"
        const val CHANNEL_PAIRING = "pairing"
        const val CHANNEL_PLAN = "plan"
        const val CONNECTION_ID = Int.MAX_VALUE - 1
        const val LOGGED_OUT_ID = Int.MAX_VALUE - 3
        const val PLAN_ID = Int.MAX_VALUE - 5
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

    /** Adaptive icon for a group: the group glyph on a full-bleed colour. */
    fun groupIcon(context: Context, seed: String): IconCompat {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(colorFor(seed))
        // The middle two thirds of an adaptive icon survive the launcher's mask; the glyph sits well inside.
        val inset = (SIZE * 0.32f).toInt()
        androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_group_avatar)?.run {
            setBounds(inset, inset, SIZE - inset, SIZE - inset)
            draw(canvas)
        }
        return IconCompat.createWithAdaptiveBitmap(out)
    }

    /** Adaptive icon: the contact photo center-cropped, or a full-bleed colour with an initial. */
    fun icon(name: String, seed: String, photo: Bitmap?): IconCompat {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (photo != null) {
            // Only the middle two thirds of an adaptive icon show through the mask, so the photo fills
            // just that, as it fills the circle in the app; full-bleed, the face came out zoomed in.
            canvas.drawColor(colorFor(seed))
            val side = minOf(photo.width, photo.height)
            val src = android.graphics.Rect((photo.width - side) / 2, (photo.height - side) / 2, (photo.width + side) / 2, (photo.height + side) / 2)
            val inset = SIZE / 6
            canvas.drawBitmap(photo, src, android.graphics.Rect(inset, inset, SIZE - inset, SIZE - inset), Paint(Paint.FILTER_BITMAP_FLAG))
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
