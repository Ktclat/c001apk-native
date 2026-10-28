// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object MediaImageLoader {
    @Volatile private var instance: ImageLoader? = null
    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: buildPublicMediaImageLoader(context).also { instance = it }
    }
}

/** One production factory for every public-media surface; Call.Factory is only a transport seam. */
internal fun buildPublicMediaImageLoader(
    context: Context,
    calls: okhttp3.Call.Factory = publicImageClient(),
): ImageLoader = ImageLoader.Builder(context.applicationContext).callFactory(calls).components {
    if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
}.eventListenerFactory(io.github.ktclat.c001apk.nativeapp.diagnostics.PublicImageEventListenerFactory()).build()

/** No API/session cookies, credentials, identity headers, or HTTPS-to-cleartext redirects. */
internal fun publicImageClient(): okhttp3.OkHttpClient = anonymousMediaClientBuilder().build()

/** Share the existing public-media loader with cache management; never create another disk cache. */
internal fun publicMediaImageLoader(context: Context): ImageLoader = MediaImageLoader.get(context)

/** Offline render fixtures can inject image bytes without touching production clients or making requests. */
val LocalMediaImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

@Composable
private fun mediaLoader(): ImageLoader {
    LocalMediaImageLoader.current?.let { return it }
    val context = LocalContext.current
    return remember(context) { publicMediaImageLoader(context) }
}

@Composable
fun MediaGrid(images: List<String>, imageLoader: ImageLoader? = null) {
    if (images.isEmpty()) return
    var selected by rememberSaveable(images) { mutableIntStateOf(-1) }
    val loader = imageLoader ?: mediaLoader()
    val states = remember(images.toList(), loader) { images.map { MediaImageState() } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val layout = mediaGridLayout(maxWidth.value, images.size, sourceImageSize(images.first()))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            images.take(9).chunked(layout.columns).forEachIndexed { row, urls ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    urls.forEachIndexed { column, url ->
                        val index = row * layout.columns + column
                        val shape = RoundedCornerShape(12.dp)
                        Box(Modifier.size(layout.width.coerceAtLeast(48f).dp, layout.height.coerceAtLeast(48f).dp).testTag("media-cell-$index")
                            .semantics { contentDescription = "查看原图 ${index + 1}" }
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), shape).clip(shape)
                            .clickable { selected = index }) {
                            MediaImage(thumbnailUrl(url), index, loader, states[index], "grid", compact = true,
                                contentScale = if (images.size == 1) ContentScale.FillBounds else ContentScale.Crop,
                                modifier = Modifier.size(layout.width.dp, layout.height.dp))
                            mediaBadge(url)?.let { badge -> Text(badge, Modifier.align(Alignment.BottomEnd).padding(5.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp)).padding(4.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 12.sp, lineHeight = 12.sp) }
                        }
                    }
                }
            }
            if (images.size > 9) TextButton(onClick = { selected = 9 }, modifier = Modifier.heightIn(min = 48.dp).testTag("media-more")) { Text("共 ${images.size} 张图片") }
        }
    }
    if (selected >= 0) Dialog(onDismissRequest = { selected = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalMediaImageLoader provides loader) {
            ImageGallery(images, selected) { selected = -1 }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImageGallery(images: List<String>, initialIndex: Int = 0, onBack: () -> Unit) {
    // A changed list is a new gallery owner, including equal URLs at different positions.
    // Recreate the pager before any toolbar or transfer action can read an obsolete index.
    val snapshot = images.toList()
    key(snapshot, initialIndex) { ImageGalleryContent(snapshot, initialIndex, onBack) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ImageGalleryContent(images: List<String>, initialIndex: Int, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val transfer = remember(context) { MediaTransfer(context) }
    val scope = rememberCoroutineScope()
    val loader = mediaLoader()
    val states = remember(images.toList(), loader) { images.map { MediaImageState() } }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<MediaTransfer.Download?>(null) }
    DisposableEffect(Unit) { onDispose { pendingDownload?.file?.delete(); pendingDownload = null } }
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { target ->
        val downloaded = pendingDownload; pendingDownload = null
        if (target == null) downloaded?.file?.delete()
        if (target != null && downloaded == null) message = "图片状态已恢复，请重新点击保存"
        if (target != null && downloaded != null) scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            busy = true
            try { transfer.save(downloaded, target); message = "图片已保存" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "保存失败，请检查目标文件夹" }
            finally { transfer.discard(downloaded); busy = false }
        }
    }
    val saveAll = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            busy = true; var count = 0
            try {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                for (url in images) {
                    val downloaded = transfer.fetch(url)
                    var target: android.net.Uri? = null
                    try {
                        withContext(Dispatchers.IO) { target = checkNotNull(DocumentsContract.createDocument(context.contentResolver, parent, downloaded.mime, downloaded.file.name)) }
                        transfer.save(downloaded, checkNotNull(target))
                        count++; message = "已保存 $count/${images.size}"
                    } catch (error: Exception) {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { target?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } } }
                        throw error
                    } finally { transfer.discard(downloaded) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "已保存 $count/${images.size}，其余保存失败，可重试" }
            finally { busy = false }
        }
    }
    Scaffold(modifier = Modifier.testTag("media-gallery"), topBar = { TopAppBar(title = { Text(if (images.isEmpty()) "没有图片" else "${pager.currentPage + 1}/${images.size}", Modifier.testTag("media-page-count")) }, navigationIcon = { TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("media-back")) { Text("返回") } }) }, bottomBar = {
        Column(Modifier.navigationBarsPadding().padding(8.dp)) {
            message?.let { Text(it) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (images.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(modifier = Modifier.heightIn(min = 48.dp).testTag("media-previous"), enabled = pager.currentPage > 0, onClick = { scope.launch { pager.scrollToPage(pager.currentPage - 1) } }) { Text("上一张") }
                TextButton(modifier = Modifier.heightIn(min = 48.dp).testTag("media-next"), enabled = pager.currentPage < images.lastIndex, onClick = { scope.launch { pager.scrollToPage(pager.currentPage + 1) } }) { Text("下一张") }
            }
            if (images.isNotEmpty()) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy, onClick = { scope.launch {
                    busy = true
                    try { pendingDownload = transfer.fetch(images[pager.currentPage]); save.launch(pendingDownload!!.file.name) }
                    catch (cancelled: CancellationException) { pendingDownload?.let { transfer.discard(it) }; pendingDownload = null; throw cancelled }
                    catch (_: Exception) { pendingDownload?.let { transfer.discard(it) }; pendingDownload = null; message = "下载图片失败，请重试" }
                    finally { busy = false }
                } }) { Text("保存") }
                TextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy, onClick = { saveAll.launch(null) }) { Text("保存全部") }
                TextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy, onClick = { scope.launch {
                    busy = true
                    try {
                        val downloaded = transfer.fetch(images[pager.currentPage])
                        try { transfer.share(downloaded) } catch (error: Exception) { transfer.discard(downloaded); throw error }
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "分享失败，请重试" }
                    finally { busy = false }
                } }) { Text("分享") }
                TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("图片链接", images[pager.currentPage])); message = "已复制图片链接" }) { Text("复制链接") }
            }
        }
    }) { padding ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(padding), key = { "${it}:${images[it]}" }) { index ->
            var zoom by remember(index, images[index]) { mutableFloatStateOf(1f) }
            var x by remember(index, images[index]) { mutableFloatStateOf(0f) }; var y by remember(index, images[index]) { mutableFloatStateOf(0f) }
            Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                MediaImage(images[index], index, loader, states[index], "viewer", modifier = Modifier.fillMaxSize()
                    .pointerInput(index, images[index]) { awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1 || zoom > 1f) {
                                val pan = event.calculatePan(); zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                                x = if (zoom == 1f) 0f else (x + pan.x).coerceIn(-size.width * (zoom - 1) / 2, size.width * (zoom - 1) / 2)
                                y = if (zoom == 1f) 0f else (y + pan.y).coerceIn(-size.height * (zoom - 1) / 2, size.height * (zoom - 1) / 2)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    } }
                    .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = x; translationY = y })
                if (states[index].phase == MediaImagePhase.Success) Row(Modifier.align(Alignment.BottomCenter)) {
                    TextButton(enabled = states[index].phase == MediaImagePhase.Success, modifier = Modifier.heightIn(min = 48.dp).testTag("media-zoom-$index"), onClick = { zoom = (zoom + 0.5f).coerceAtMost(5f) }) { Text("放大") }
                    TextButton(enabled = states[index].phase == MediaImagePhase.Success, modifier = Modifier.heightIn(min = 48.dp).testTag("media-fit-$index"), onClick = { zoom = 1f; x = 0f; y = 0f }) { Text("适合屏幕") }
                }
            }
        }
    }
}

/** The failure branch does not compose an AsyncImage, so revisiting a failed page cannot retry it. */
@Composable
private fun MediaImage(url: String, index: Int, loader: ImageLoader, state: MediaImageState, surface: String,
    modifier: Modifier = Modifier, compact: Boolean = false, contentScale: ContentScale = ContentScale.Fit) {
    val phase = state.phase
    val attempt = state.attempt
    val context = LocalContext.current
    DisposableEffect(state) { onDispose { state.leave() } }
    val request = remember(context, url, state, attempt) {
        ImageRequest.Builder(context).data(url).apply {
            // A successful HTTP response can still contain undecodable cached bytes. An explicit
            // retry bypasses reads for this request only, while repairing its normal cache entry.
            // Never clear shared caches or change transfer/download clients.
            if (state.bypassCache) {
                memoryCachePolicy(CachePolicy.WRITE_ONLY)
                diskCachePolicy(CachePolicy.WRITE_ONLY)
            }
        }.build()
    }
    Box(Modifier.fillMaxSize().testTag("media-$surface-image-$index").semantics {
        stateDescription = when (phase) {
            MediaImagePhase.Loading -> "图片 ${index + 1}，加载中"
            MediaImagePhase.Success -> "图片 ${index + 1}，已加载"
            MediaImagePhase.Error -> "图片 ${index + 1}，加载失败"
        }
    }, contentAlignment = Alignment.Center) {
        if (phase != MediaImagePhase.Error) key(state, attempt) {
            AsyncImage(request, if (compact) null else "图片 ${index + 1}，双指缩放", imageLoader = loader,
                onSuccess = { state.finish(attempt, MediaImagePhase.Success) },
                onError = { state.finish(attempt, MediaImagePhase.Error, imageLoadFailure(it.result.throwable)) },
                contentScale = contentScale, modifier = modifier.fillMaxSize().testTag("media-$surface-content-$index"))
        }
        when (phase) {
            MediaImagePhase.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(if (compact) 24.dp else 36.dp).testTag("media-$surface-loading-$index"))
            }
            MediaImagePhase.Error -> if (compact) {
                Text("加载失败", Modifier.padding(4.dp).testTag("media-grid-error-$index"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.failure?.displayText() ?: "图片加载失败", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("media-viewer-failure-reason-$index"))
                TextButton(onClick = state::retry, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
                    .testTag("media-viewer-retry-$index").semantics { contentDescription = "重试图片 ${index + 1}" }) {
                    Text("重试本图")
                }
            }
            MediaImagePhase.Success -> Unit
        }
    }
}
