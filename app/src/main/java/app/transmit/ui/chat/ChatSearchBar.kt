package app.transmit.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.transmit.R
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The chat top bar's ⋮ menu: Search, and Chat settings (which tapping the name also opens). */
@Composable
internal fun ChatOverflowMenu(onSearch: () -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.chat_more_options)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_search)) }, onClick = { open = false; onSearch() })
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_settings)) }, onClick = { open = false; onSettings() })
        }
    }
}

/**
 * The search bar, in place of the chat's top bar while searching: back closes it, then the field, a clear ✕, the
 * "3 of 12" count and the arrows (up to older matches, down to newer). Under it, "No matches", or the look further
 * back on the phone.
 */
@Composable
internal fun ChatSearchBar(state: SearchUi, search: ChatSearch) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(onBack = search::close)
    LaunchedEffect(Unit) {
        // The field is typed into at once; after a rotation, what was typed is searched again.
        search.type(text)
        focus.requestFocus()
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = search::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.search_close)) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                    if (text.isEmpty()) {
                        Text(stringResource(R.string.search_hint), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            search.type(it)
                        },
                        singleLine = true,
                        textStyle = style,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            search.newest()
                            keyboard?.hide()
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (text.isNotEmpty()) {
                    IconButton(onClick = {
                        text = ""
                        search.type("")
                        focus.requestFocus()
                    }) { Icon(Icons.Default.Close, stringResource(R.string.search_clear)) }
                }
                if (state.hits.isNotEmpty()) {
                    Text(
                        stringResource(R.string.search_count, state.index + 1, state.hits.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                IconButton(onClick = search::older, enabled = state.canGoOlder) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.search_older))
                }
                IconButton(onClick = search::newer, enabled = state.canGoNewer) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.search_newer))
                }
            }
            val note = when {
                state.status == SearchStatus.LOOKING_BACK -> stringResource(R.string.search_looking_back)
                state.status == SearchStatus.NO_MORE -> stringResource(R.string.search_no_more)
                state.noMatches -> stringResource(R.string.search_no_matches)
                else -> null
            }
            if (note != null) {
                Row(
                    Modifier.padding(start = 56.dp, end = 16.dp, bottom = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.status == SearchStatus.LOOKING_BACK) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Scrolls the chat to the current match, centred, each time search goes to one, once it's in the list. */
@Composable
internal fun SearchScroll(state: SearchUi, items: List<MessageUi>, listState: LazyListState) {
    val latest by rememberUpdatedState(items)
    val key = state.current?.key
    LaunchedEffect(state.jump) {
        if (key == null || !state.open) return@LaunchedEffect
        // A match further back is loaded into the list first.
        val index = withTimeoutOrNull(REVEAL_MS) { snapshotFlow { listIndexOf(latest, key) }.filterNotNull().first() } ?: return@LaunchedEffect
        listState.centerOn(index)
    }
}

/** Centres item [index] of this list (which runs up from the bottom), as far as the list's ends allow. */
private suspend fun LazyListState.centerOn(index: Int) {
    val viewport = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    fun shown() = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (shown() == null) animateScrollToItem(index, -viewport / 2)
    val item = shown() ?: return
    val delta = item.offset + item.size / 2 - viewport / 2
    if (delta != 0) animateScrollBy(delta.toFloat())
}

private const val REVEAL_MS = 8_000L

/** A highlighter's colours, the same in light and dark: matches in yellow, the current one in orange. */
private val MatchStyle = SpanStyle(background = Color(0xFFFFE57F), color = Color(0xFF1F1A10))
private val CurrentStyle = SpanStyle(background = Color(0xFFFFAB40), color = Color(0xFF1F1A10))

/**
 * [content] with [mark]'s matches in [text] highlighted, where [text] starts at [start] in [content]. Links keep
 * working; their colour gives way to the highlight's, so a match is readable in any bubble.
 */
internal fun highlightMatches(content: AnnotatedString, text: String, start: Int, mark: SearchMark?): AnnotatedString {
    if (mark == null) return content
    val ranges = SearchText.find(text, mark.query).filter { start + it.last < content.length }
    if (ranges.isEmpty()) return content
    val style = if (mark.current) CurrentStyle else MatchStyle
    return buildAnnotatedString {
        append(content.text)
        content.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
        content.paragraphStyles.forEach { addStyle(it.item, it.start, it.end) }
        content.getStringAnnotations(0, content.length).forEach { addStringAnnotation(it.tag, it.item, it.start, it.end) }
        content.getLinkAnnotations(0, content.length).forEach { r ->
            when (val link = r.item) {
                is LinkAnnotation.Url -> addLink(LinkAnnotation.Url(link.url, link.styles?.colorless(), link.linkInteractionListener), r.start, r.end)
                is LinkAnnotation.Clickable -> addLink(LinkAnnotation.Clickable(link.tag, link.styles?.colorless(), link.linkInteractionListener), r.start, r.end)
            }
        }
        ranges.forEach { addStyle(style, start + it.first, start + it.last + 1) }
    }
}

private fun TextLinkStyles.colorless() = TextLinkStyles(
    style = style?.copy(color = Color.Unspecified),
    focusedStyle = focusedStyle?.copy(color = Color.Unspecified),
    hoveredStyle = hoveredStyle?.copy(color = Color.Unspecified),
    pressedStyle = pressedStyle?.copy(color = Color.Unspecified),
)

/**
 * The bubble's text as [BubbleText] builds it into [content] (a bold subject line, then [MessageUi.displayText]),
 * with [mark]'s matches highlighted.
 */
internal fun highlightBubble(content: AnnotatedString, item: MessageUi, mark: SearchMark?): AnnotatedString {
    if (mark == null) return content
    val text = item.displayText
    val subject = item.message.subject?.takeIf { it.isNotBlank() }
    val start = if (subject == null) 0 else subject.length + if (text.isNotBlank()) 1 else 0
    return highlightMatches(content, text, start, mark)
}
