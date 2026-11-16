package app.transmit.ui.media

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.transmit.messaging.Attachment
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Opening, sharing and saving MMS parts. Parts are copied out of the telephony provider first. */
object MediaActions {
    suspend fun export(context: Context, a: Attachment): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(a.mimeType) ?: a.name?.substringAfterLast('.', "bin") ?: "bin"
        val base = a.name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "part${a.partId}"
        val file = File(dir, "${base.replace(Regex("[^\\w.-]"), "_")}.$ext")
        val input = context.contentResolver.openInputStream(a.uri) ?: error("Attachment unavailable")
        input.use { i -> file.outputStream().use { i.copyTo(it) } }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    suspend fun open(context: Context, a: Attachment) {
        val uri = runCatching { export(context, a) }.getOrElse { toast(context, "Couldn't open attachment"); return }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(context, "No app can open ${a.mimeType}")
        }
    }

    suspend fun share(context: Context, a: Attachment) {
        val uri = runCatching { export(context, a) }.getOrElse { toast(context, "Couldn't share attachment"); return }
        val send = Intent(Intent.ACTION_SEND).setType(a.mimeType).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun save(context: Context, a: Attachment) {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val collection = when {
                    a.isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    a.isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    a.isAudio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }
                val dir = when {
                    a.isImage || a.isVideo -> Environment.DIRECTORY_PICTURES
                    a.isAudio -> Environment.DIRECTORY_MUSIC
                    else -> Environment.DIRECTORY_DOWNLOADS
                }
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(a.mimeType) ?: "bin"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, a.name ?: "Transmit_${System.currentTimeMillis()}.$ext")
                    put(MediaStore.MediaColumns.MIME_TYPE, a.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Transmit")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val dest = resolver.insert(collection, values) ?: error("insert failed")
                resolver.openInputStream(a.uri)!!.use { i -> resolver.openOutputStream(dest)!!.use { i.copyTo(it) } }
                resolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }.isSuccess
        }
        toast(context, if (ok) "Saved" else "Couldn't save")
    }

    private suspend fun toast(context: Context, text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

/** Full-screen pinch-to-zoom image viewer. */
@Composable
fun ImageViewer(attachment: Attachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = attachment.uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero })
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).align(Alignment.TopStart)) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                Box(Modifier.weight(1f))
                IconButton(onClick = { scope.launch { MediaActions.save(context, attachment) } }) { Icon(Icons.Default.Download, "Save", tint = Color.White) }
                IconButton(onClick = { scope.launch { MediaActions.share(context, attachment) } }) { Icon(Icons.Default.Share, "Share", tint = Color.White) }
            }
        }
    }
}
