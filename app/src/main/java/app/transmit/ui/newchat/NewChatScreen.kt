package app.transmit.ui.newchat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.transmit.AppContainer
import app.transmit.Share
import app.transmit.data.PhoneContact
import app.transmit.gm.NotConnectedException
import app.transmit.messaging.PhoneNumbers
import app.transmit.ui.components.Avatar
import app.transmit.ui.share.SharePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Recipient(val address: String, val label: String)

class NewChatViewModel(private val c: AppContainer) : ViewModel() {
    val contacts = MutableStateFlow<List<PhoneContact>?>(null)

    init {
        viewModelScope.launch { contacts.value = if (c.contacts.canRead()) c.contacts.phoneContacts() else emptyList() }
    }

    suspend fun threadFor(recipients: List<String>): Long = withContext(Dispatchers.IO) { c.messaging.threadIdFor(recipients) }
}

internal fun looksLikeNumber(q: String) = q.count { it.isDigit() } >= 3 && q.all { it.isDigit() || it in "+-() ." }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewChatScreen(vm: NewChatViewModel, share: Share?, startInGroupMode: Boolean = false, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(listOf<Recipient>()) }
    var groupMode by rememberSaveable { mutableStateOf(startInGroupMode) }
    var dialpad by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(dialpad) { focus.requestFocus() }
    BackHandler(onBack = onBack)

    fun open(list: List<Recipient>) {
        if (list.isEmpty()) return
        scope.launch {
            // A new conversation is made on the phone, so this fails while disconnected.
            runCatching { vm.threadFor(list.map { it.address }) }
                .onSuccess(onOpen)
                .onFailure { e ->
                    snackbar.showSnackbar(if (e is NotConnectedException) "Connect to Google Messages to start a new chat" else e.message ?: "Couldn't start the chat")
                }
        }
    }
    fun pick(r: Recipient) {
        if (!groupMode && selected.isEmpty()) open(listOf(r))
        else if (selected.none { PhoneNumbers.same(it.address, r.address) }) selected = selected + r
        query = ""
    }
    val typed = query.trim().takeIf { looksLikeNumber(it) }?.let { Recipient(PhoneNumbers.normalize(it), PhoneNumbers.format(it)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (groupMode) "New group" else if (share != null) "Send to" else "New chat") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconToggleButton(checked = groupMode, onCheckedChange = { groupMode = it }) { Icon(Icons.Default.GroupAdd, "Group") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // The whole screen, not just the list, so messages show above the keyboard.
        modifier = Modifier.imePadding(),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("To", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                    selected.forEach { r ->
                        InputChip(selected = true, onClick = { selected = selected - r }, label = { Text(r.label) })
                    }
                    Box(Modifier.padding(vertical = 12.dp).width(160.dp)) {
                        if (query.isEmpty()) Text("Name or number", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = if (dialpad) KeyboardType.Phone else KeyboardType.Text, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                val list = selected + listOfNotNull(typed)
                                open(list)
                            }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                }
                IconButton(onClick = { dialpad = !dialpad }) {
                    Icon(if (dialpad) Icons.Default.Keyboard else Icons.Default.Dialpad, if (dialpad) "Keyboard" else "Dial pad")
                }
                if (selected.isNotEmpty()) {
                    FilledIconButton(onClick = { open(selected + listOfNotNull(typed)) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Start") }
                }
            }
            share?.let { SharePreview(it) }
            val q = query.trim().lowercase()
            val digits = q.filter { it.isDigit() }
            val list = contacts
            LazyColumn(Modifier.fillMaxSize()) {
                typed?.let { r ->
                    item("typed") {
                        RecipientRow(name = r.label, detail = null, seed = r.address, photo = null, icon = true) { pick(r) }
                    }
                }
                when {
                    list == null -> item("loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    else -> items(
                        list.filter { c ->
                            q.isEmpty() || c.name.lowercase().contains(q) || (digits.length >= 2 && c.number.filter(Char::isDigit).contains(digits))
                        },
                        key = { "${it.contactId}:${it.number}" },
                    ) { c ->
                        RecipientRow(c.name, "${PhoneNumbers.format(c.number)} · ${c.typeLabel}", c.number, c.photoUri, icon = false) {
                            pick(Recipient(c.number, c.name))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RecipientRow(name: String, detail: String?, seed: String, photo: android.net.Uri?, icon: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary) }
        } else {
            Avatar(name, seed, photo, size = 40.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
