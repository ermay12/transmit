package app.transmit.smart

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.CalendarContract
import android.widget.Toast
import java.time.ZoneId

/** What tapping a smart link does. */
object SmartActions {
    /** Copies a verification code, marked sensitive so the clipboard preview and keyboard suggestions hide it. */
    fun copyCode(context: Context, code: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("Verification code", code)
        // ClipDescription.EXTRA_IS_SENSITIVE (API 33); older versions ignore it.
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
        // Android 13 and later confirm copies on screen themselves.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
    }

    /** Google Maps (the app when installed, else the web) searching for the address. */
    fun openMap(context: Context, address: String) {
        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(address))
        start(context, Intent(Intent.ACTION_VIEW, uri), "No maps app found")
    }

    /** Opens the calendar's new-event screen, filled in, for the user to check and save. */
    fun addToCalendar(context: Context, time: DateParser.Result, title: String, description: String?, location: String?) {
        val zone = ZoneId.systemDefault()
        val end = time.end ?: if (time.allDay) time.start.plusDays(1) else time.start.plusHours(1)
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, time.start.atZone(zone).toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(zone).toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, time.allDay)
            .putExtra(CalendarContract.Events.TITLE, title)
        description?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        start(context, intent, "No calendar app found")
    }

    fun dial(context: Context, number: String) =
        start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)), "No phone app found")

    fun email(context: Context, address: String) =
        start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)), "No email app found")

    /** The sentence around a date, as a starting title for its event; the user edits it before saving. */
    fun eventTitle(text: String, start: Int, end: Int): String {
        val from = SENTENCE_END.findAll(text).lastOrNull { it.range.last < start }?.range?.last?.plus(1) ?: 0
        // A question keeps its mark ("¿Cenamos el viernes?"); a period doesn't.
        val to = SENTENCE_END.find(text, end)?.let { if (text[it.range.first] == '.' || text[it.range.first] == '\n') it.range.first else it.range.last + 1 }
            ?: text.length
        val sentence = text.substring(from, to).trim().replace(Regex("\\s+"), " ")
        return if (sentence.length <= 80) sentence else sentence.take(79).trimEnd() + "…"
    }

    private val SENTENCE_END = Regex("[.!?]+(?=\\s|$)|\\n")

    private fun start(context: Context, intent: Intent, missing: String) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
        }
    }
}
