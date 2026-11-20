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
import android.os.Bundle
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

    /** What the conversation notifications show between posts. */
    private val state = NotificationState()
    /** Builds and posts one at a time, so an older build is never posted over a newer one. */
    private val posting = Mutex()
    /** Bumped when a conversation's notification is cleared, so a build already under way doesn't bring it back. */
    private val clearedCount = ConcurrentHashMap<Long, Int>()
    private val seeded = AtomicBoolean(false)

    /** How a conversation's notification is being updated. */
    private enum class Update {
        /** A message arrived: it alerts, and starts afresh if the notification had gone. */
        NEW_MESSAGE,
        /** Something in it changed (a translation): updated silently, and only if it's still up. */
        REFRESH,
        /** Read elsewhere, maybe only up to a message: a silent refresh, and it goes once nothing is unread. */
        READ_ELSEWHERE,
        /** The user replied from it, or the reply couldn't go: updated silently, which also ends the reply spinner. */
        REPLY,
    }

    /**
     * Posts the notification for a conversation, stacking its unread messages: with [alert], for a new message; without,
     * it silently refreshes the notification if it's still up (one the user dismissed stays dismissed).
     */
    suspend fun showThread(threadId: Long, alert: Boolean) = update(threadId, if (alert) Update.NEW_MESSAGE else Update.REFRESH)

    /**
     * [threadId] was read elsewhere, maybe only up to a message: its notification keeps what is still unread, and goes
     * when nothing is (see [NotificationState.goesOnReadElsewhere] for one the user replied from).
     */
    suspend fun readElsewhere(threadId: Long) = update(threadId, Update.READ_ELSEWHERE)

    /**
     * The user replied [text] from [threadId]'s notification. It stays up, showing what it showed with "You: …" after
     * it, and doesn't alert. Call before marking the conversation read, so the received messages are still unread.
     */
    suspend fun replied(threadId: Long, text: String) = withContext(Dispatchers.IO) {
        posting.withLock {
            seed()
            state.replied(threadId, state.lines(threadId, repo.unreadIncoming(threadId, NotificationState.MAX_LINES)), text, System.currentTimeMillis())
            post(threadId, Update.REPLY)
        }
    }

    /** A reply from [threadId]'s notification didn't go (or was empty): the notification shows as it was, without the spinner. */
    suspend fun replyNotSent(threadId: Long) = update(threadId, Update.REPLY)

    private suspend fun update(threadId: Long, how: Update) = withContext(Dispatchers.IO) {
        posting.withLock {
            seed()
            post(threadId, how)
        }
    }

    private suspend fun post(threadId: Long, how: Update) {
        if (threadId == activeThreadId || !canPost()) return
        val showing = state.isShowing(threadId)
        when {
            (how == Update.REFRESH || how == Update.READ_ELSEWHERE) && !showing -> return
            // Dismissed, opened or read since: a new one shows only what's unread.
            how == Update.NEW_MESSAGE && !showing -> state.gone(threadId)
            else -> Unit
        }
        val generation = clearedCount[threadId]
        val unread = repo.unreadIncoming(threadId, NotificationState.MAX_LINES)
        val lines = state.lines(threadId, unread)
        if (lines.isEmpty() || (how == Update.READ_ELSEWHERE && state.goesOnReadElsewhere(threadId, unread))) {
            remove(threadId)
            return
        }
        val s = settings.current()
        val eff = policy.effective(threadId, s)
        if (eff.muted) return
        val received = lines.mapNotNull { (it as? NotificationState.Line.Received)?.message }
        // A reaction quotes its target in the original language, so it shows the target's translation.
        val reactionTargets = received.mapNotNull { m ->
            val r = Reactions.parse(m.body)?.takeIf { it.quoted != null } ?: return@mapNotNull null
            repo.reactionTarget(threadId, m.date, r)?.let { m.key to it.key }
        }.toMap()
        val translated = dao.translations((received.map { it.key } + reactionTargets.values).distinct(), eff.target.code)
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
        val display = s.scriptDisplay(eff.target)
        // The summary lists each chat by what it received last.
        var summaryText = ""
        var newestAt = 0L
        for (line in lines) {
            when (line) {
                is NotificationState.Line.Received -> {
                    val m = line.message
                    val address = m.sender ?: recipients.firstOrNull().orEmpty()
                    val person = people.getOrPut(address) { shortcuts.person(address) }
                    // A reaction quotes in one line; a message gets its pinyin or romaji under it.
                    val translation = reactionTargets[m.key]?.let { translated[it]?.shown(display) } ?: translated[m.key]?.shownWithReading(display)
                    val text = notificationText(m, translation, s.notificationContent, eff.autoTranslate)
                    summaryText = text
                    newestAt = m.date
                    style.addMessage(text, m.date, person)
                }
                is NotificationState.Line.Replied -> {
                    // From the user: the style's own person, "You".
                    val text = if (s.notificationContent == NotificationContent.HIDDEN) context.getString(R.string.notify_reply_hidden) else line.text
                    newestAt = line.at
                    style.addMessage(NotificationCompat.MessagingStyle.Message(text, line.at, null as Person?))
                }
            }
        }

        // Labelled like the conversation list, so the share sheet and launcher show the same name.
        val label = chatTitle(recipients.map(contacts::lookup), customName)
        val shortcutId = shortcuts.push(threadId, label, if (isGroup) null else people.values.firstOrNull(), recipients.firstOrNull() ?: title)
        val alert = how == Update.NEW_MESSAGE
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
            .setDeleteIntent(dismissedIntent(threadId))
            .setWhen(newestAt)
            // Several conversations bundle under one summary, which never alerts itself.
            .setGroup(GROUP_MESSAGES)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .addExtras(Bundle().apply { putLong(EXTRA_THREAD_ID, threadId) })
        // The newest verification code gets a one-tap copy, ahead of the other actions. On Android 16 the system
        // offers its own copy chip too; one button is enough, and ours also marks the code read.
        received.asReversed().firstNotNullOfOrNull { Otp.find(it.body) }?.let {
            builder.addAction(copyCodeAction(threadId, it.code, showCode = s.notificationContent != NotificationContent.HIDDEN))
            builder.setAllowSystemGeneratedContextualActions(false)
        }
        builder
            .addAction(replyAction(threadId))
            .addAction(markReadAction(threadId))
        if (!alert) builder.setSilent(true)
        // Opened, read or dismissed while this was being built.
        if (clearedCount[threadId] != generation) return
        val posted = runCatching { nm.notify(notificationId(threadId), builder.build()) }.isSuccess
        if (posted) {
            state.posted(NotificationState.Shown(threadId, title, summaryText, newestAt), lines)
            updateSummary()
        }
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

    /** Clears [threadId]'s notification: the chat was opened or marked read. */
    fun cancel(threadId: Long) {
        clearedCount.merge(threadId, 1, Int::plus)
        seed()
        remove(threadId)
    }

    /** The user dismissed [threadId]'s notification. */
    fun dismissed(threadId: Long) {
        seed()
        state.gone(threadId)
        updateSummary()
    }

    /** The user dismissed the whole bundle. */
    fun allDismissed() {
        seed()
        state.allGone()
        updateSummary()
    }

    private fun remove(threadId: Long) {
        state.gone(threadId)
        nm.cancel(notificationId(threadId))
        updateSummary()
    }

    /** After a restart, the conversation notifications still up are known from what the system shows. */
    private fun seed() {
        if (!seeded.compareAndSet(false, true)) return
        val active = runCatching { nm.activeNotifications }.getOrNull().orEmpty()
        summaryUp = active.any { it.id == SUMMARY_ID && it.tag == null }
        for (sbn in active) {
            val threadId = sbn.notification.extras.getLong(EXTRA_THREAD_ID, -1)
            if (threadId < 0 || sbn.id != notificationId(threadId)) continue
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(NotificationCompat.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(NotificationCompat.EXTRA_TITLE)
            val text = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)
            state.posted(NotificationState.Shown(threadId, title?.toString().orEmpty(), text?.toString().orEmpty(), sbn.notification.`when`))
        }
    }

    @Volatile private var summaryUp = false
    private var summaryShows: List<NotificationState.Shown>? = null

    /**
     * Two or more conversations bundle under a summary. It never alerts (the conversation that changed does). Once one
     * conversation is left the system shows it on its own, and the summary stays until none is: cancelling a summary
     * cancels the notifications under it too.
     */
    @Synchronized
    private fun updateSummary() {
        val chats = state.showing()
        if (chats.isEmpty()) {
            if (summaryUp || summaryShows != null) nm.cancel(SUMMARY_ID)
            summaryUp = false
            summaryShows = null
            return
        }
        if (chats.size < 2 || chats == summaryShows) return
        val inbox = NotificationCompat.InboxStyle()
        chats.forEach { inbox.addLine(if (it.text.isEmpty()) it.title else "${it.title}: ${it.text}") }
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(context.getString(R.string.notify_summary_chats, chats.size))
            .setContentText(chats.joinToString(", ") { it.title })
            .setStyle(inbox)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(chats.first().at)
            .setGroup(GROUP_MESSAGES)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(context, SUMMARY_ID, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context, SUMMARY_ID,
                    Intent(context, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_DISMISSED_ALL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        if (runCatching { nm.notify(SUMMARY_ID, n) }.isSuccess) {
            summaryUp = true
            summaryShows = chats
        }
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

    /** Day 6 of the free week. [canBuy] is false when not installed from Play, where subscribing needs the Play version. */
    fun notifyTrialEndsTomorrow(canBuy: Boolean) = notifyPlan(R.string.notify_trial_ends_tomorrow, keepGoing(canBuy))

    /** The trial or subscription ended (or the trial's AI ran out) while the app wasn't open. Tapping opens Plans. */
    fun notifyAccessEnded(reason: EndReason, canBuy: Boolean) = notifyPlan(AccessText.ended(reason), keepGoing(canBuy))

    private fun keepGoing(canBuy: Boolean) = if (canBuy) R.string.access_keep_going else R.string.play_needed_subscribe

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

    private fun dismissedIntent(threadId: Long): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_DISMISSED)
            .putExtra(NotificationActionReceiver.EXTRA_THREAD, threadId)
        return PendingIntent.getBroadcast(context, notificationId(threadId), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

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
        /** The summary that bundles the conversations' notifications. */
        const val SUMMARY_ID = Int.MAX_VALUE - 7
        const val GROUP_MESSAGES = "app.transmit.MESSAGES"
        private const val EXTRA_THREAD_ID = "app.transmit.THREAD_ID"
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
