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
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object MediaImageLoader {
    @Volatile private var instance: ImageLoader? = null
    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext).components {
            if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build().also { instance = it }
    }
}

/** Offline render fixtures can inject image bytes without touching production clients or making requests. */
val LocalMediaImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

@Composable
private fun mediaLoader(): ImageLoader {
    LocalMediaImageLoader.current?.let { return it }
    val context = LocalContext.current
    return remember(context) { MediaImageLoader.get(context) }
}

@Composable
fun MediaGrid(images: List<String>, imageLoader: ImageLoader? = null) {
    if (images.isEmpty()) return
    var selected by rememberSaveable(images) { mutableIntStateOf(-1) }
    val loader = imageLoader ?: mediaLoader()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val layout = mediaGridLayout(maxWidth.value, images.size, sourceImageSize(images.first()))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            images.take(9).chunked(layout.columns).forEachIndexed { row, urls ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    urls.forEachIndexed { column, url ->
                        val index = row * layout.columns + column
                        val shape = RoundedCornerShape(12.dp)
                        var loaded by remember(url) { mutableStateOf(false) }
                        Box(Modifier.size(layout.width.dp, layout.height.dp).testTag("media-cell-$index")
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), shape).clip(shape)
                            .clickable { selected = index }) {
                            if (!loaded) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                                Text("∞", color = MaterialTheme.colorScheme.outline, fontSize = 45.sp, lineHeight = 45.sp)
                            }
                            AsyncImage(thumbnailUrl(url), "图片 ${index + 1}，点击查看", imageLoader = loader,
                                onLoading = { loaded = false }, onSuccess = { loaded = true }, onError = { loaded = false },
                                contentScale = if (images.size == 1) ContentScale.FillBounds else ContentScale.Crop,
                                modifier = Modifier.fillMaxSize())
                            mediaBadge(url)?.let { badge -> Text(badge, Modifier.align(Alignment.BottomEnd).padding(5.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp)).padding(4.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 12.sp, lineHeight = 12.sp) }
                        }
                    }
                }
            }
            if (images.size > 9) Text("共 ${images.size} 张图片", Modifier.clickable { selected = 9 })
        }
    }
    if (selected >= 0) Dialog(onDismissRequest = { selected = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImageGallery(images, selected) { selected = -1 }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGallery(images: List<String>, initialIndex: Int = 0, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val transfer = remember(context) { MediaTransfer(context) }
    val scope = rememberCoroutineScope()
    val loader = mediaLoader()
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
    Scaffold(topBar = { TopAppBar(title = { Text(if (images.isEmpty()) "没有图片" else "${pager.currentPage + 1}/${images.size}") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }, bottomBar = {
        Column(Modifier.navigationBarsPadding().padding(8.dp)) {
            message?.let { Text(it) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (images.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = pager.currentPage > 0, onClick = { scope.launch { pager.scrollToPage(pager.currentPage - 1) } }) { Text("上一张") }
                TextButton(enabled = pager.currentPage < images.lastIndex, onClick = { scope.launch { pager.scrollToPage(pager.currentPage + 1) } }) { Text("下一张") }
            }
            if (images.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(enabled = !busy, onClick = { scope.launch {
                    busy = true
                    try { pendingDownload = transfer.fetch(images[pager.currentPage]); save.launch(pendingDownload!!.file.name) }
                    catch (cancelled: CancellationException) { pendingDownload?.let { transfer.discard(it) }; pendingDownload = null; throw cancelled }
                    catch (_: Exception) { pendingDownload?.let { transfer.discard(it) }; pendingDownload = null; message = "下载图片失败，请重试" }
                    finally { busy = false }
                } }) { Text("保存") }
                TextButton(enabled = !busy, onClick = { saveAll.launch(null) }) { Text("保存全部") }
                TextButton(enabled = !busy, onClick = { scope.launch {
                    busy = true
                    try {
                        val downloaded = transfer.fetch(images[pager.currentPage])
                        try { transfer.share(downloaded) } catch (error: Exception) { transfer.discard(downloaded); throw error }
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "分享失败，请重试" }
                    finally { busy = false }
                } }) { Text("分享") }
                TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("图片链接", images[pager.currentPage])); message = "已复制图片链接" }) { Text("复制链接") }
            }
        }
    }) { padding ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(padding)) { index ->
            var zoom by remember(index) { mutableFloatStateOf(1f) }
            var x by remember(index) { mutableFloatStateOf(0f) }; var y by remember(index) { mutableFloatStateOf(0f) }
            Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                AsyncImage(images[index], "图片 ${index + 1}，双指缩放", imageLoader = loader, modifier = Modifier.fillMaxSize()
                    .pointerInput(index) { awaitEachGesture {
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
                Row(Modifier.align(Alignment.BottomCenter)) {
                    TextButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(5f) }) { Text("放大") }
                    TextButton(onClick = { zoom = 1f; x = 0f; y = 0f }) { Text("适合屏幕") }
                }
            }
        }
    }
}
