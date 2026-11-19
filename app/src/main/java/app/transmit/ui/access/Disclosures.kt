package app.transmit.ui.access

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import app.transmit.R
import app.transmit.ai.ProviderType

/** The privacy policy, served by the Worker. Google Play requires a link to it inside the app. */
const val PRIVACY_POLICY_URL = "https://transmit.ermay12.workers.dev/privacy"

/**
 * [text], then "Privacy policy" as a link: the AI disclosure where translation is turned on, or the link alone when
 * [text] is null. Small print, but never hidden.
 */
@Composable
fun PrivacyNote(text: String?, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Center, style: TextStyle = MaterialTheme.typography.bodySmall) {
    val link = stringResource(R.string.privacy_policy)
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline))
    val annotated = buildAnnotatedString {
        if (text != null) {
            append(text)
            append(' ')
        }
        withLink(LinkAnnotation.Url(PRIVACY_POLICY_URL, linkStyle)) { append(link) }
    }
    Text(annotated, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = textAlign, modifier = modifier)
}

/** What happens to messages in the free week and the plans. */
@Composable
fun managedAiDisclosure(): String = stringResource(R.string.ai_disclosure_managed)

/** What happens to messages on the user's own key with [provider]. */
@Composable
fun ownKeyAiDisclosure(provider: ProviderType): String =
    if (provider == ProviderType.CUSTOM) stringResource(R.string.ai_disclosure_own_key_custom)
    else stringResource(R.string.ai_disclosure_own_key, provider.label)
