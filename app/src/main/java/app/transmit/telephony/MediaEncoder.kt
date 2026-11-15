package app.transmit.telephony

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max

class AttachmentTooLargeException(val name: String, val limitBytes: Int) :
    Exception("$name is larger than the ${limitBytes / 1024} KB MMS limit")

/** Shrinks picked media so a whole MMS stays under the carrier's size limit. */
class MediaEncoder(private val context: Context) {

    data class Encoded(val bytes: ByteArray, val mimeType: String, val name: String)

    fun encode(attachment: OutgoingAttachment, budgetBytes: Int, index: Int): Encoded {
        val resolver = context.contentResolver
        val mime = attachment.mimeType.ifBlank { resolver.getType(attachment.uri) ?: "application/octet-stream" }
        val displayName = attachment.name ?: queryName(attachment.uri)
        if (mime.startsWith("image/") && mime != "image/gif") {
            return Encoded(compressImage(attachment.uri, budgetBytes), "image/jpeg", "image$index.jpg")
        }
        val bytes = resolver.openInputStream(attachment.uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot read attachment")
        if (bytes.size > budgetBytes) {
            if (mime == "image/gif") return Encoded(compressImage(attachment.uri, budgetBytes), "image/jpeg", "image$index.jpg")
            throw AttachmentTooLargeException(displayName ?: "Attachment", budgetBytes)
        }
        val ext = mime.substringAfter('/').substringBefore(';').take(8)
        return Encoded(bytes, mime, displayName?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "file$index.$ext")
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun compressImage(uri: Uri, budgetBytes: Int): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var maxDim = 1600
        val longest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / (sample * 2) >= maxDim) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Cannot decode image")
        var bitmap = rotateForExif(uri, decoded)

        while (true) {
            val scaled = scaleTo(bitmap, maxDim)
            for (quality in intArrayOf(85, 75, 65, 55, 45, 35)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= budgetBytes) return out.toByteArray()
            }
            if (maxDim <= 320) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 25, out)
                return out.toByteArray()
            }
            maxDim = (maxDim * 0.7).toInt()
            bitmap = scaled
        }
    }

    private fun scaleTo(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun rotateForExif(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: return bitmap
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }
}
