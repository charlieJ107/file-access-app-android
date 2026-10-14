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
import androidx.core.graphics.createBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import space.zhuoling.fileaccess.R
import space.zhuoling.fileaccess.core.model.EntryRef
import space.zhuoling.fileaccess.core.model.RemoteEntry
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException
import space.zhuoling.fileaccess.core.transfer.RemoteAccess
import space.zhuoling.fileaccess.preview.PreviewRepository
import space.zhuoling.fileaccess.preview.PreviewViewModel
import space.zhuoling.fileaccess.thumbnail.MediaKind
import space.zhuoling.fileaccess.thumbnail.mediaKind
import space.zhuoling.fileaccess.userMessage

@Composable
fun PreviewScreen(model: PreviewViewModel, repository: PreviewRepository, remote: RemoteAccess, onDownload: (RemoteEntry) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    when {
        state.restoring || state.sessionId.isEmpty() -> PreviewLoading()
        state.items.isEmpty() -> PreviewFailure(detail = state.error, onRetry = model::retry)
        else -> key(state.sessionId) {
            val loadedEntries = remember { mutableStateMapOf<EntryRef, RemoteEntry>() }
            Column(Modifier.fillMaxSize()) {
                if (state.error != null) PreviewFailure(detail = state.error, onRetry = model::retry)
                MediaPreviewPager(
                    items = state.items, initialIndex = state.currentIndex, partial = state.partial,
                    onSettled = model::select, onDownload = { onDownload(loadedEntries[it.ref] ?: it) },
                    modifier = Modifier.weight(1f),
                ) { item, active, dragging, onScrubbing ->
                    if (active) PreviewItem(
                        item, repository, remote, dragging,
                        position = model::position,
                        onPositionSaved = model::savePosition,
                        onLoaded = { loadedEntries[item.ref] = it },
                        onScrubbing = onScrubbing,
                        expectedConfigRevision = state.connectionRevision,
                    ) else PreviewPlaceholder(item)
                }
            }
        }
    }
}

/** Only the settled page composes this loader. Scrolling cancels pending remote work. */
@Composable
private fun PreviewItem(
    item: RemoteEntry, repository: PreviewRepository, remote: RemoteAccess, dragging: Boolean,
    position: (RemoteEntry) -> Long, onPositionSaved: (RemoteEntry, Long) -> Unit, onLoaded: (RemoteEntry) -> Unit,
    onScrubbing: (Boolean) -> Unit, expectedConfigRevision: Long?,
) {
    var retry by remember(item.ref) { mutableIntStateOf(0) }
    key(item.ref, retry) {
        var entry by remember { mutableStateOf<RemoteEntry?>(null) }
        var mime by remember { mutableStateOf("") }
        var text by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
        var file by remember { mutableStateOf<File?>(null) }
        var error by remember { mutableStateOf<Int?>(null) }
        var errorDetail by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(true) }
        val latestLoaded by rememberUpdatedState(onLoaded)
        LaunchedEffect(dragging) {
            if (dragging || !loading) return@LaunchedEffect
            try {
                val loaded = repository.stat(item.ref, expectedConfigRevision)
                val loadedMime = repository.mime(loaded)
                val kind = mediaKind(loaded)
                val loadedText = if (loadedMime.startsWith("text/") || loadedMime in setOf("application/json", "application/xml")) {
                    repository.text(loaded, expectedConfigRevision)
                } else null
                val loadedFile = if (kind == MediaKind.IMAGE || loadedMime == "application/pdf") {
                    repository.cachedFile(loaded, expectedConfigRevision, forceReload = retry > 0)
                } else null
                // Blocking adapters can finish after cancellation; never publish their stale result.
                coroutineContext.ensureActive()
                entry = loaded
                mime = loadedMime
                text = loadedText
                file = loadedFile
                latestLoaded(loaded)
                loading = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                coroutineContext.ensureActive()
                error = if ((failure as? StorageException)?.error == StorageError.QUOTA_EXCEEDED) R.string.preview_too_large else R.string.preview_error
                errorDetail = (failure as? StorageException)?.takeUnless { it.error == StorageError.QUOTA_EXCEEDED }?.userMessage()
                loading = false
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                error != null -> PreviewFailure(error!!, detail = errorDetail, onRetry = { retry++ })
                loading -> PreviewLoading()
                text != null -> Column(Modifier.fillMaxSize()) {
                    if (text!!.second) Text(stringResource(R.string.preview_truncated), style = MaterialTheme.typography.labelMedium)
                    val chunks = remember(text) { text!!.first.chunked(4096) }
                    SelectionContainer {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(chunks) { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                }
                file != null && mediaKind(entry!!) == MediaKind.IMAGE -> {
                    val context = LocalContext.current
                    val request = remember(file) {
                        ImageRequest.Builder(context).data(file).memoryCacheKey("${file!!.path}:${file!!.lastModified()}:$retry").build()
                    }
                    AsyncImage(request, entry!!.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                        onError = { error = R.string.preview_error })
                }
                file != null && mime == "application/pdf" -> PdfPreview(file!!, onRetry = { retry++ })
                entry != null && mediaKind(entry!!) in setOf(MediaKind.VIDEO, MediaKind.AUDIO) -> MediaPreviewPlayer(
                    entry = entry!!, remote = remote, active = true, isPagerDragging = dragging,
                    initialPositionMs = position(entry!!), onPositionSaved = { onPositionSaved(entry!!, it) },
                    onError = { error = R.string.preview_error }, onControlScrubbingChanged = onScrubbing,
                    modifier = Modifier.fillMaxSize(),
                    expectedConfigRevision = expectedConfigRevision,
                )
                else -> Text(stringResource(R.string.preview_unsupported))
            }
        }
    }
}

@Composable
internal fun PreviewLoading() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Text(stringResource(R.string.preview_loading), Modifier.padding(8.dp))
    }
}

@Composable
internal fun PreviewFailure(message: Int = R.string.preview_error, detail: String? = null, onRetry: () -> Unit) {
    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(detail ?: stringResource(message))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.preview_retry)) }
    }
}

@Composable
private fun PdfPreview(file: File, onRetry: () -> Unit) {
    var page by rememberSaveable(file.path) { mutableIntStateOf(0) }
    var count by remember(file) { mutableIntStateOf(0) }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file, page) {
        bitmap = null
        failed = false
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
            failed -> PreviewFailure(onRetry = onRetry)
            bitmap != null -> Image(bitmap!!.asImageBitmap(), stringResource(R.string.pdf_page, page + 1, count), Modifier.weight(1f), contentScale = ContentScale.Fit)
            else -> CircularProgressIndicator()
        }
    }
}
