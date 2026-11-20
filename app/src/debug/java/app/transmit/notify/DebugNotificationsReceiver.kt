package app.transmit.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.transmit.app
import app.transmit.data.db.TranslationEntity
import app.transmit.data.db.TranslationOrigin
import app.transmit.gm.GmEvent
import app.transmit.gm.GmMapping
import app.transmit.gm.proto.Conversation
import app.transmit.gm.proto.MessageContent
import app.transmit.gm.proto.MessageInfo
import app.transmit.gm.proto.MessageStatus
import app.transmit.gm.proto.MessageStatusType
import app.transmit.gm.proto.Participant
import app.transmit.gm.proto.SmallInfo
import app.transmit.messaging.Message
import kotlinx.coroutines.launch
import app.transmit.gm.proto.Message as GmMessage

/**
 * Fake updates from the phone, from adb, for trying conversation notifications on an unpaired emulator. They go
 * through the bridge as the phone's own updates do. DUMP keeps other apps out (only the shell holds it).
 *
 * ```
 * R=com.ermay12.transmit/app.transmit.notify.DebugNotificationsReceiver
 * adb shell am broadcast -n $R --es do incoming --es conv 901 --es from 'Ana López:+15555550100' --es text 'Hola'
 * #   a group: --es from 'Ana:+15555550100,Ben:+15555550101' --es name 'Climbing' --es sender +15555550101
 * #   with a translation: --es translation 'Hi'
 * adb shell am broadcast -n $R --es do reply --es conv 901 --es text 'On my way'   # an inline reply that went
 * adb shell am broadcast -n $R --es do read --es conv 901                          # read on the phone
 * ```
 */
class DebugNotificationsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.app.container
        val convId = intent.getStringExtra("conv") ?: return
        val threadId = GmMapping.threadId(convId)
        val pending = goAsync()
        c.scope.launch {
            try {
                when (intent.getStringExtra("do")) {
                    "incoming" -> {
                        val now = System.currentTimeMillis()
                        val conv = conversation(c.bridge.conversation(threadId), convId, intent.getStringExtra("from"), intent.getStringExtra("name"))
                        val sender = intent.getStringExtra("sender")
                            ?.let { n -> conv.participantsList.firstOrNull { !it.isMe && it.id.number == n } }
                            ?: conv.participantsList.first { !it.isMe }
                        val id = "dbg$convId-$now"
                        val text = intent.getStringExtra("text").orEmpty()
                        intent.getStringExtra("translation")?.let { tr ->
                            val lang = c.policy.effective(threadId, c.settings.current()).target.code
                            c.dao.upsertTranslation(
                                TranslationEntity(Message.key(id), lang, threadId, text.trim().hashCode(), tr, TranslationOrigin.AI, null, 0.0, 0, 0, now),
                            )
                        }
                        c.bridge.handle(
                            GmEvent.ConversationUpdate(conv.toBuilder().setUnread(true).setLatestMessageID(id).setLastMessageTimestamp(now * 1000).build()),
                        )
                        val message = GmMessage.newBuilder()
                            .setMessageID(id)
                            .setConversationID(convId)
                            .setParticipantID(sender.id.participantID)
                            .setTimestamp(now * 1000)
                            .setMessageStatus(MessageStatus.newBuilder().setStatus(MessageStatusType.INCOMING_COMPLETE))
                            .addMessageInfo(MessageInfo.newBuilder().setMessageContent(MessageContent.newBuilder().setContent(text)))
                            .build()
                        c.bridge.handle(GmEvent.MessageUpdate(message, isOld = false))
                    }
                    // What the notification's reply does once the message is on its way.
                    "reply" -> {
                        c.notifier.replied(threadId, intent.getStringExtra("text").orEmpty())
                        c.messaging.markThreadRead(threadId)
                    }
                    "read" -> {
                        val conv = c.bridge.conversation(threadId) ?: return@launch
                        val newest = c.db.gmDao().messages(threadId, 1).firstOrNull()
                        c.bridge.handle(
                            GmEvent.ConversationUpdate(
                                conv.toBuilder().setUnread(false)
                                    .apply { newest?.let { setLatestMessageID(it.messageId).setLastMessageTimestamp(it.timestamp * 1000) } }
                                    .build(),
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("DebugNotifications", "Fake update failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** [existing], or a new conversation with the people in [from] ("Name:+number,…"), named [name] when a group. */
    private fun conversation(existing: Conversation?, convId: String, from: String?, name: String?): Conversation {
        if (existing != null && from == null) return existing
        val people = from.orEmpty().split(',').filter { it.isNotBlank() }.map { it.substringBeforeLast(':').trim() to it.substringAfterLast(':').trim() }
        val b = Conversation.newBuilder()
            .setConversationID(convId)
            .setIsGroupChat(people.size > 1)
            .setDefaultOutgoingID("1")
            .addParticipants(Participant.newBuilder().setID(SmallInfo.newBuilder().setParticipantID("1")).setIsMe(true))
        people.forEachIndexed { i, (person, number) ->
            b.addParticipants(
                Participant.newBuilder()
                    .setID(SmallInfo.newBuilder().setParticipantID("${i + 2}").setNumber(number))
                    .setFullName(person)
                    .setFirstName(person.substringBefore(' '))
                    .setFormattedNumber(number)
                    .setIsVisible(true),
            )
        }
        if (people.size > 1 && name != null) b.name = name
        return b.build()
    }
}
