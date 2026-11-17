package app.transmit.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import app.transmit.messaging.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class ContactInfo(
    val address: String,
    val name: String?,
    val photoUri: Uri?,
    val lookupUri: Uri?,
) {
    val isContact get() = lookupUri != null
    /** Formatting a number is slow enough to matter across a long conversation list, so it's done once. */
    val displayName: String by lazy { name ?: PhoneNumbers.format(address) }
    /** For group titles: a contact's first name, or the whole number ("+1 555…" must not shrink to "+1"). */
    val shortName get() = name?.substringBefore(' ') ?: displayName
}

/** A conversation's title: the user's name for it if set, else its people (first names in a group). */
fun chatTitle(people: List<ContactInfo>, customName: String? = null): String =
    customName?.takeIf { it.isNotBlank() } ?: people.joinToString(", ") { if (people.size > 1) it.shortName else it.displayName }

data class PhoneContact(
    val contactId: Long,
    val name: String,
    val number: String,
    val typeLabel: String,
    val photoUri: Uri?,
)

class ContactsRepository(private val context: Context) {
    private val cache = ConcurrentHashMap<String, ContactInfo>()
    private val _version = MutableStateFlow(0)
    /** Bumps whenever the address book changes so UIs can re-resolve names. */
    val version: StateFlow<Int> = _version

    private var observing = false

    /** Names Google Messages knows for numbers that aren't in this phone's address book. */
    private val fallbackNames = ConcurrentHashMap<String, String>()

    /** Photos Google Messages has for people, used when the address book has none. */
    val profilePhotos = ProfilePhotos(context)

    /** Stores the phone's photos for these addresses; ones without a contact photo pick them up. */
    fun addPhotos(photos: List<Pair<String, ByteArray>>) {
        var changed = false
        for ((address, bytes) in photos) {
            val key = PhoneNumbers.matchKey(address)
            if (key.isBlank() || bytes.isEmpty() || !profilePhotos.save(key, bytes)) continue
            if (cache[key]?.let { it.photoUri == null || it.photoUri.scheme == "file" } == true) cache.remove(key)
            changed = true
        }
        if (changed) _version.value++
    }

    fun addNames(names: List<Pair<String, String>>) {
        var changed = false
        for ((address, name) in names) {
            if (address.isBlank() || name.isBlank() || PhoneNumbers.format(address) == name) continue
            val key = PhoneNumbers.matchKey(address)
            if (fallbackNames.put(key, name) != name) {
                if (cache[key]?.isContact == false) cache.remove(key)
                changed = true
            }
        }
        if (changed) _version.value++
    }

    fun canRead() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun observeIfNeeded() {
        if (observing || !canRead()) return
        observing = true
        context.contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI, true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    cache.clear()
                    _version.value++
                }
            },
        )
    }

    /** Blocking lookup; call from a background thread. */
    fun lookup(address: String): ContactInfo {
        val key = PhoneNumbers.matchKey(address)
        cache[key]?.let { return it }
        observeIfNeeded()
        val found = if (!canRead() || address.isBlank()) {
            ContactInfo(address, null, null, null)
        } else {
            runCatching {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI, ContactsContract.PhoneLookup.LOOKUP_KEY, ContactsContract.PhoneLookup._ID),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        ContactInfo(
                            address = address,
                            name = c.getString(0),
                            photoUri = c.getString(1)?.let(Uri::parse),
                            lookupUri = ContactsContract.Contacts.getLookupUri(c.getLong(3), c.getString(2)),
                        )
                    } else null
                }
            }.getOrNull() ?: ContactInfo(address, null, null, null)
        }
        val info = found.copy(name = found.name ?: fallbackNames[key], photoUri = found.photoUri ?: profilePhotos.photo(key))
        cache[key] = info
        return info
    }

    /**
     * Resolves many numbers with one read of the address book instead of a lookup each. Matches like
     * PhoneLookup: same last seven digits, then a loose caller-ID comparison. Blocking.
     */
    fun prefetch(addresses: Collection<String>) {
        if (!canRead()) return
        observeIfNeeded()
        val todo = addresses.filter { a ->
            a.isNotBlank() && a.none { it.isLetter() } && '@' !in a && !cache.containsKey(PhoneNumbers.matchKey(a))
        }.distinctBy { PhoneNumbers.matchKey(it) }
        if (todo.isEmpty()) return
        data class Entry(val number: String, val info: ContactInfo)
        val index = HashMap<String, MutableList<Entry>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI, ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
        )
        runCatching {
            context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0) ?: continue
                    val info = ContactInfo(number, c.getString(1), c.getString(2)?.let(Uri::parse), ContactsContract.Contacts.getLookupUri(c.getLong(4), c.getString(3)))
                    index.getOrPut(number.filter { it.isDigit() }.takeLast(7)) { mutableListOf() } += Entry(number, info)
                }
            }
        }.onFailure { return }
        for (a in todo) {
            val match = index[a.filter { it.isDigit() }.takeLast(7)]?.firstOrNull { PhoneNumberUtils.compare(it.number, a) }
            val key = PhoneNumbers.matchKey(a)
            val info = match?.info?.copy(address = a) ?: ContactInfo(a, fallbackNames[key], null, null)
            cache[key] = if (info.photoUri == null) info.copy(photoUri = profilePhotos.photo(key)) else info
        }
    }

    suspend fun lookupAll(addresses: Collection<String>): Map<String, ContactInfo> = withContext(Dispatchers.IO) {
        addresses.associateWith { lookup(it) }
    }

    suspend fun phoneContacts(): List<PhoneContact> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext emptyList()
        val out = mutableListOf<PhoneContact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
        )?.use { c ->
            val seen = HashSet<String>()
            while (c.moveToNext()) {
                val number = c.getString(2) ?: continue
                val contactId = c.getLong(0)
                if (!seen.add("$contactId:${PhoneNumbers.matchKey(number)}")) continue
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                out += PhoneContact(contactId, c.getString(1) ?: number, number, label, c.getString(5)?.let(Uri::parse))
            }
        }
        out
    }
}
