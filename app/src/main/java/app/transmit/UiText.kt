package app.transmit

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Text for the user that logic can hand back without a Context: a string resource with its arguments, a date, or
 * text that is already plain (what a BYOK user's provider said). Arguments may be [UiText] too. It becomes a string
 * where it's shown, with [resolve] or, in Compose, [string].
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A short date, such as "Dec 3". */
    data class Date(val at: Long) : UiText

    data class Plain(val text: String) : UiText

    fun resolve(context: Context): String = when (this) {
        is Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
        is Date -> DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        is Plain -> text
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

@Composable
fun UiText.string(): String = resolve(LocalContext.current)
