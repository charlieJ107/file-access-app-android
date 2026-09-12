package space.zhuoling.fileaccess.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.core.graphics.createBitmap
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.zhuoling.fileaccess.R
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException
import space.zhuoling.fileaccess.core.transfer.RemoteAccess
import space.zhuoling.fileaccess.preview.PreviewRepository
import space.zhuoling.fileaccess.preview.RemoteMediaDataSource

@Composable
fun PreviewScreen(ref: EntryRef, repository: PreviewRepository, remote: RemoteAccess, onDownload: (RemoteEntry) -> Unit) {
    var entry by remember(ref) { mutableStateOf<RemoteEntry?>(null) }
    var mime by remember(ref) { mutableStateOf("") }
    var text by remember(ref) { mutableStateOf<Pair<String, Boolean>?>(null) }
    var file by remember(ref) { mutableStateOf<File?>(null) }
    var error by remember(ref) { mutableStateOf<Int?>(null) }
    var loading by remember(ref) { mutableStateOf(true) }
    LaunchedEffect(ref) {
        try {
            val loaded = remote.withSession(ref.connectionId) { it.stat(ref) }
            entry = loaded
            mime = repository.mime(loaded)
            when {
                mime.startsWith("text/") || mime in setOf("application/json", "application/xml") -> text = repository.text(loaded)
                mime.startsWith("image/") || mime == "application/pdf" -> file = repository.cachedFile(loaded)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            error = if ((failure as? StorageException)?.error == StorageError.QUOTA_EXCEEDED) R.string.preview_too_large else R.string.preview_error
        } finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        entry?.let { loaded ->
            Text(loaded.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            TextButton(onClick = { onDownload(loaded) }) { Text(stringResource(R.string.download_file)) }
        }
        when {
            loading -> { CircularProgressIndicator(); Text(stringResource(R.string.preview_loading)) }
            error != null -> Text(stringResource(error!!))
            text != null -> {
                if (text!!.second) Text(stringResource(R.string.preview_truncated), style = MaterialTheme.typography.labelMedium)
                val chunks = remember(text) { text!!.first.chunked(4096) }
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(chunks) { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
            file != null && mime.startsWith("image/") -> AsyncImage(file, entry?.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            file != null && mime == "application/pdf" -> PdfPreview(file!!)
            entry != null && (mime.startsWith("video/") || mime.startsWith("audio/")) -> MediaPreview(entry!!, remote)
            else -> Text(stringResource(R.string.preview_unsupported))
        }
    }
}

@Composable
private fun PdfPreview(file: File) {
    var page by rememberSaveable(file.path) { mutableIntStateOf(0) }
    var count by remember(file) { mutableIntStateOf(0) }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file, page) {
        bitmap = null
        try {
            val rendered = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val number = renderer.pageCount
                        renderer.openPage(page.coerceIn(0, number - 1)).use { pdf ->
                            val scale = minOf(2f, 2048f / pdf.width, 4096f / pdf.height)
                            val result = createBitmap(maxOf(1, (pdf.width * scale).toInt()), maxOf(1, (pdf.height * scale).toInt()))
                            result.eraseColor(Color.WHITE)
                            pdf.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            number to result
                        }
                    }
                }
            }
            count = rendered.first
            bitmap = rendered.second
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { page-- }, enabled = page > 0) { Text(stringResource(R.string.previous_page)) }
            Text(stringResource(R.string.pdf_page, page + 1, count))
            TextButton(onClick = { page++ }, enabled = page + 1 < count) { Text(stringResource(R.string.next_page)) }
        }
        when {
            failed -> Text(stringResource(R.string.preview_error))
            bitmap != null -> Image(bitmap!!.asImageBitmap(), stringResource(R.string.pdf_page, page + 1, count), Modifier.weight(1f), contentScale = ContentScale.Fit)
            else -> CircularProgressIndicator()
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun MediaPreview(entry: RemoteEntry, remote: RemoteAccess) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failed by remember(entry.ref) { mutableStateOf(false) }
    val player = remember(entry.ref, entry.revision) {
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(
            DataSource.Factory { RemoteMediaDataSource(entry, remote) },
        )).build().apply {
            setMediaItem(MediaItem.fromUri("fileaccess://preview/${android.net.Uri.encode(entry.name)}"))
            prepare()
        }
    }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    if (failed) Text(stringResource(R.string.preview_error))
    AndroidView(factory = { PlayerView(it).apply { this.player = player; keepScreenOn = true } },
        update = { it.player = player }, modifier = Modifier.fillMaxSize(), onRelease = { it.player = null })
}
