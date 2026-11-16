package app.transmit.gm

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import app.transmit.app
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/**
 * Serves message attachments as `content://<package>.media/<messageId>/<index>`. Google Messages
 * keeps them encrypted on Google's servers; the first open downloads and decrypts one into the cache.
 */
class MediaProvider : ContentProvider() {
    override fun onCreate() = true

    private fun bridge() = context!!.app.container.bridge

    private fun parse(uri: Uri): Pair<String, Int> {
        val segments = uri.pathSegments
        if (segments.size != 2) throw FileNotFoundException(uri.toString())
        return segments[0] to (segments[1].toIntOrNull() ?: throw FileNotFoundException(uri.toString()))
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Attachments are read-only")
        val (messageId, index) = parse(uri)
        val (file, _) = try {
            runBlocking { bridge().mediaFile(messageId, index) }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for $uri", e)
            throw FileNotFoundException("Couldn't download the attachment: ${e.message}")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = runCatching {
        val (messageId, index) = parse(uri)
        runBlocking { bridge().mediaInfo(messageId, index) }?.mimeType
    }.getOrNull()

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val (messageId, index) = runCatching { parse(uri) }.getOrNull() ?: return null
        val media = runBlocking { bridge().mediaInfo(messageId, index) } ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map {
                    when (it) {
                        OpenableColumns.DISPLAY_NAME -> media.mediaName.ifEmpty { "attachment" }
                        OpenableColumns.SIZE -> media.size
                        else -> null
                    }
                },
            )
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        const val TAG = "MediaProvider"
    }
}
