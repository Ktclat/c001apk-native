// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.launch

@Composable
fun MutationStatus(state: DeliveryState, onCorrect: () -> Unit = {}) {
    when (state) {
        DeliveryState.Ready -> Unit
        DeliveryState.Sending -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is DeliveryState.Finished -> when (val result = state.outcome) {
            is WriteOutcome.Confirmed -> Text("服务端已确认")
            is WriteOutcome.Uncertain -> Text(result.message, color = MaterialTheme.colorScheme.error)
            is WriteOutcome.Rejected -> {
                Text(result.message, color = MaterialTheme.colorScheme.error)
                if (result.kind == FailureKind.CAPTCHA) Text("需要人工验证；原生验证码续传尚未接入，本次不会重发")
                else TextButton(onClick = onCorrect) { Text("修改后重试") }
            }
        }
    }
}

/** Route owner must key this screen by account generation to discard another account's draft. */
@Composable
fun SocialComposerScreen(repository: SocialRepository, generation: Long, initial: ComposerDraft = ComposerDraft("")) {
    key(generation, initial.replyId, initial.targetId) {
        var text by remember { mutableStateOf(initial.text) }
        var option by remember { mutableStateOf(if (initial.replyId == null) initial.selfOnly else initial.replyAndForward) }
        val runner = remember { MutationRunner() }
        val state by runner.state.collectAsState()
        val scope = rememberCoroutineScope()
        Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (initial.replyId == null) "发布动态" else "回复", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("内容") },
                modifier = Modifier.fillMaxWidth().weight(1f), enabled = state == DeliveryState.Ready)
            Row { Checkbox(checked = option, onCheckedChange = { option = it }, enabled = state == DeliveryState.Ready)
                Text(if (initial.replyId == null) "仅自己可见" else "回复并转发", modifier = Modifier.padding(top = 12.dp)) }
            Text("图片上传尚未接入；当前支持文字和系统键盘表情", style = MaterialTheme.typography.bodySmall)
            MutationStatus(state) { runner.allowCorrection() }
            Button(enabled = text.isNotBlank() && state == DeliveryState.Ready, onClick = {
                scope.launch { runner.submit(isCurrent = { repository.isCurrentGeneration(generation) }) { repository.publish(initial.copy(text = text, selfOnly = option, replyAndForward = option), expectedGeneration = generation) } }
            }, modifier = Modifier.fillMaxWidth()) { Text("确认发送") }
        }
    }
}

@Composable
fun NotificationScreen(repository: SocialRepository, generation: Long,
    onConversation: (conversationKey: String, uid: String) -> Unit = { _, _ -> }) {
    key(generation) {
        var category by remember { mutableStateOf(NoticeCategory.MENTIONS) }
        var menu by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            Box { TextButton(onClick = { menu = true }) { Text(category.title) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    NoticeCategory.entries.forEach { item -> DropdownMenuItem(text = { Text(item.title) }, onClick = { category = item; menu = false }) }
                }
            }
            key(category) { SocialRowsScreen(conversationList = category == NoticeCategory.MESSAGES, load = { repository.notices(category, it) }, onClick = { row ->
                row.conversationKey?.let { onConversation(it, row.uid) }
            }, actions = { row, refresh ->
                if (category == NoticeCategory.MESSAGES && row.conversationKey != null) {
                    ConversationActions(repository, generation, row, refresh)
                } else if (category != NoticeCategory.MESSAGES && canDeleteNotification(row) && row.notificationScope?.generation == generation) {
                    SocialDeleteButton(repository, generation, DeleteTarget.NOTIFICATION, row.id, notificationRow = row, onConfirmed = refresh)
                }
            }) }
        }
    }
}

@Composable
private fun SocialRowsScreen(conversationList: Boolean = false, load: suspend (PageCursor) -> ApiResult<Page<SocialRow>>, onClick: (SocialRow) -> Unit,
    actions: @Composable (SocialRow, () -> Unit) -> Unit = { _, _ -> }) {
    var rows by remember { mutableStateOf<List<SocialRow>>(emptyList()) }
    var next by remember { mutableStateOf<PageCursor?>(PageCursor()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun fetch(reset: Boolean = false) {
        if (loading) return
        val cursor = if (reset) PageCursor() else next ?: return
        loading = true; error = null
        try { when (val result = load(cursor)) {
            is ApiResult.Failure -> error = result.message
            is ApiResult.Success -> { rows = mergeSocialRows(if (reset) emptyList() else rows, result.value.items, conversationList); next = result.value.nextCursor }
        } } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "无法读取内容，请稍后重试" }
        finally { loading = false }
    }
    LaunchedEffect(Unit) { fetch() }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { scope.launch { fetch(true) } }, enabled = !loading) { Text("刷新") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(rows, key = { index, row -> if (conversationList) row.conversationKey ?: row.id else row.id }) { _, row ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(row.userName.ifBlank { "通知" }, style = MaterialTheme.typography.titleSmall)
                    Text(row.message)
                    if (row.imageUrl != null) Text("图片消息")
                    if (conversationList) row.conversationKey?.let { TextButton(onClick = { onClick(row) }) { Text(if (row.pinned) "置顶会话 · 打开" else "打开会话") } }
                    actions(row) { scope.launch { fetch(true) } }
                } }
            }
            item { if (!loading && rows.isEmpty() && error == null) Text("暂无内容") }
            item { if (next != null) TextButton(enabled = !loading, onClick = { scope.launch { fetch() } }) { Text("加载更多") } }
        }
    }
}

@Composable
fun ChatScreen(repository: SocialRepository, generation: Long, conversationKey: String, recipientUid: String, favorites: FavoriteFeedLoader? = null) {
    key(generation, conversationKey) {
        var text by remember { mutableStateOf("") }
        var shareFeed by remember { mutableStateOf(false) }
        var feedPicker by remember { mutableStateOf(false) }
        var selectedImage by remember { mutableStateOf<SelectedImage?>(null) }
        var staging by remember { mutableStateOf(false) }
        var imageError by remember { mutableStateOf<String?>(null) }
        val context = LocalContext.current
        val runner = remember { MutationRunner() }
        val state by runner.state.collectAsState()
        val scope = rememberCoroutineScope()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                staging = true; imageError = null
                try { selectedImage = stageSelectedImage(context.applicationContext, uri) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { imageError = "无法读取图片，请选择20MB以内的JPEG、PNG、WebP或GIF" }
                finally { staging = false }
            }
        }
        val retainedImage = selectedImage
        DisposableEffect(retainedImage) { onDispose { retainedImage?.close() } }
        if (feedPicker) FeedSharePicker(repository, favorites, onSelected = { url -> text = url; shareFeed = true; feedPicker = false }, onDismiss = { feedPicker = false })
        Column(Modifier.fillMaxSize().imePadding().padding(12.dp)) {
            Text("私信", style = MaterialTheme.typography.headlineSmall)
            Box(Modifier.weight(1f)) { SocialRowsScreen(load = { repository.chat(conversationKey, it) }, onClick = {}, actions = { row, _ ->
                if (row.hasImage) MessageImage(repository, row)
            }) }
            MutationStatus(state) { runner.allowCorrection() }
            if ((state as? DeliveryState.Finished)?.outcome is WriteOutcome.Confirmed) {
                TextButton(onClick = { if (runner.beginNext()) { text = ""; selectedImage = null } }) { Text("写下一条消息") }
            }
            Row {
                TextButton(enabled = !staging && state == DeliveryState.Ready, onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/gif")) }) { Text("选择图片") }
                TextButton(enabled = selectedImage == null && state == DeliveryState.Ready, onClick = { feedPicker = true }) { Text("选择动态") }
            }
            selectedImage?.let { image ->
                AsyncImage(model = ImageRequest.Builder(context).data(image.file).memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build(), contentDescription = "待发送图片", modifier = Modifier.height(96.dp))
                TextButton(enabled = state == DeliveryState.Ready, onClick = { selectedImage = null }) { Text("移除图片") }
            }
            if (staging) LinearProgressIndicator(Modifier.fillMaxWidth())
            imageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row { Checkbox(checked = shareFeed, onCheckedChange = { shareFeed = it }, enabled = state == DeliveryState.Ready)
                Text("分享动态链接", modifier = Modifier.padding(top = 12.dp)) }
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                enabled = selectedImage == null && state == DeliveryState.Ready, label = { Text(if (shareFeed) "动态编号或酷安动态链接" else "消息") })
            Text("图片发送包含上传和投递两步；结果未确认时请先刷新核实", style = MaterialTheme.typography.bodySmall)
            Button(enabled = validId(recipientUid) && (selectedImage != null || text.isNotBlank()) && !staging && state == DeliveryState.Ready,
                onClick = { scope.launch { runner.submit(isCurrent = { repository.isCurrentGeneration(generation) }) {
                    val image = selectedImage
                    if (image != null) repository.sendImage(recipientUid, image, expectedGeneration = generation)
                    else if (shareFeed) repository.sendFeed(recipientUid, text, expectedGeneration = generation) else repository.sendText(recipientUid, text, expectedGeneration = generation)
                } } }, modifier = Modifier.fillMaxWidth()) { Text("确认发送") }
        }
    }
}

/** Caller refreshes account-specific action state after acknowledgement; no optimistic count changes. */
@Composable
fun SocialActionBar(repository: SocialRepository, generation: Long, id: String, reply: Boolean = false,
    liked: Boolean? = null, onReply: () -> Unit = {}, onConfirmed: () -> Unit = {}) {
    key(generation, id, liked) {
        val runner = remember { MutationRunner() }; val state by runner.state.collectAsState()
        val scope = rememberCoroutineScope()
        Column {
            Row {
                TextButton(enabled = state == DeliveryState.Ready && liked != null, onClick = { scope.launch {
                    runner.submit(isCurrent = { repository.isCurrentGeneration(generation) }) { repository.like(id, reply, liked != true, expectedGeneration = generation) }
                    if ((runner.state.value as? DeliveryState.Finished)?.outcome is WriteOutcome.Confirmed) onConfirmed()
                } }) { Text(if (liked == true) "取消赞" else "点赞") }
                TextButton(onClick = onReply) { Text("回复") }
            }
            if (liked == null) Text("点赞状态尚未确认", style = MaterialTheme.typography.bodySmall)
            MutationStatus(state) { runner.allowCorrection() }
        }
    }
}

@Composable
fun SocialFollowButton(repository: SocialRepository, generation: Long, target: FollowTarget, id: String,
    followed: Boolean, onConfirmed: () -> Unit = {}) {
    key(generation, target, id, followed) {
        val runner = remember { MutationRunner() }; val state by runner.state.collectAsState()
        val scope = rememberCoroutineScope()
        Column {
            TextButton(enabled = state == DeliveryState.Ready, onClick = { scope.launch {
                runner.submit(isCurrent = { repository.isCurrentGeneration(generation) }) { repository.follow(target, id, !followed, expectedGeneration = generation) }
                if ((runner.state.value as? DeliveryState.Finished)?.outcome is WriteOutcome.Confirmed) onConfirmed()
            } }) { Text(if (followed) "取消关注" else "关注") }
            MutationStatus(state) { runner.allowCorrection() }
        }
    }
}

/** Always asks in-app immediately before deleting a remote item. No deletion on navigation. */
@Composable
fun SocialDeleteButton(repository: SocialRepository, generation: Long, target: DeleteTarget, id: String,
    conversationKey: String? = null, notificationRow: SocialRow? = null, onConfirmed: () -> Unit = {}) {
    key(generation, target, id, conversationKey) {
        var confirm by remember { mutableStateOf(false) }
        val runner = remember { MutationRunner() }; val state by runner.state.collectAsState()
        val scope = rememberCoroutineScope()
        Column {
            TextButton(enabled = state == DeliveryState.Ready, onClick = { confirm = true }) { Text("删除") }
            MutationStatus(state) { runner.allowCorrection() }
        }
        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("确认删除？") },
            text = { Text("将删除服务端内容，此操作可能无法撤销。") },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
            confirmButton = { TextButton(onClick = {
                confirm = false
                scope.launch { runner.submit(isCurrent = { repository.isCurrentGeneration(generation) }) {
                    if (target == DeleteTarget.NOTIFICATION) notificationRow?.let { repository.deleteNotification(it) }
                        ?: WriteOutcome.Rejected(FailureKind.INVALID_ARGUMENT, "通知来源未确认，请刷新后重试")
                    else repository.delete(target, id, conversationKey, expectedGeneration = generation)
                }
                    if ((runner.state.value as? DeliveryState.Finished)?.outcome is WriteOutcome.Confirmed) onConfirmed()
                }
            }) { Text("确认删除") } })
    }
}

@Composable
private fun ConversationActions(repository: SocialRepository, generation: Long, row: SocialRow, refresh: () -> Unit) {
    key(generation, row.conversationKey, row.pinned) {
        val runner = remember { MutationRunner() }; val state by runner.state.collectAsState()
        val scope = rememberCoroutineScope()
        var confirmDelete by remember { mutableStateOf(false) }
        Column {
            TextButton(enabled = state == DeliveryState.Ready, onClick = { scope.launch {
                runner.submit(isCurrent = { repository.isCurrentGeneration(generation) }) { repository.conversationPinned(row.conversationKey!!, !row.pinned, expectedGeneration = generation) }
                if ((runner.state.value as? DeliveryState.Finished)?.outcome is WriteOutcome.Confirmed) refresh()
            } }) { Text(if (row.pinned) "取消置顶" else "置顶") }
            MutationStatus(state) { runner.allowCorrection() }
            TextButton(enabled = state == DeliveryState.Ready, onClick = { confirmDelete = true }) { Text("删除会话") }
        }
        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除会话？") },
            text = { Text("会话将从服务端列表移除，此操作可能无法撤销。") },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
            confirmButton = { TextButton(onClick = {
                confirmDelete = false
                scope.launch { runner.submit(isCurrent = { repository.isCurrentGeneration(generation) }) { repository.delete(DeleteTarget.CONVERSATION, row.id, row.conversationKey, expectedGeneration = generation) }
                    if ((runner.state.value as? DeliveryState.Finished)?.outcome is WriteOutcome.Confirmed) refresh()
                }
            }) { Text("确认删除") } })
    }
}
