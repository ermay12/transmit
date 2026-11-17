package app.transmit.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Profile photos Google Messages has for people that the address book doesn't: RCS profile
 * pictures and business logos. Kept on disk by number match key, so they show before the phone answers.
 */
class ProfilePhotos(context: Context) {
    private val dir = File(context.filesDir, "profile-photos")
    private val index: ConcurrentHashMap<String, Uri> by lazy { load() }

    fun photo(key: String): Uri? = index[key]

    /** Stores [bytes] as [key]'s photo. Returns whether it differs from the one already stored. */
    @Synchronized
    fun save(key: String, bytes: ByteArray): Boolean {
        val file = File(dir, fileName(key))
        if (file.length() == bytes.size.toLong() && runCatching { file.readBytes().contentEquals(bytes) }.getOrDefault(false)) return false
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) return false
        }.onFailure { tmp.delete(); return false }
        index[key] = uriFor(file)
        return true
    }

    private fun load(): ConcurrentHashMap<String, Uri> {
        val map = ConcurrentHashMap<String, Uri>()
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp")) { f.delete(); return@forEach }
            keyOf(f.name)?.let { map[it] = uriFor(f) }
        }
        return map
    }

    /** Names the content too, so image caches don't keep serving a photo the person has since changed. */
    private fun uriFor(file: File): Uri {
        val crc = CRC32().apply { update(file.readBytes()) }.value
        return Uri.fromFile(file).buildUpon().appendQueryParameter("v", crc.toString(36)).build()
    }

    private fun fileName(key: String) = Base64.encodeToString(key.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun keyOf(name: String) = runCatching { String(Base64.decode(name, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }.getOrNull()
}
