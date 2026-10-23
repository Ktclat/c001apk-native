// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import io.github.ktclat.c001apk.nativeapp.ui.FlutterMaterialIcons
import java.util.UUID

private fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.hostActivity() else null
    else -> null
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FeedComposerUi(repository: SocialRepository, generation: Long, initial: ComposerDraft, showTitle: Boolean = true, onSent: () -> Unit = {}) {
    key(generation, initial.replyId, initial.targetId, initial.targetType, initial.replyToReply) {
        var instance by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
        val factory = remember { object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ComposerSlotViewModel() as T
        } }
        val slot: ComposerSlotViewModel = viewModel(key = "social-composer-route-owner", factory = factory)
        val model = remember(slot, generation, instance) { slot.acquire(generation, instance, initial) }
        if (model.isAbandoned) {
            LaunchedEffect(model) { instance = UUID.randomUUID().toString() }
        } else {
        val context = LocalContext.current
        val activity = context.hostActivity()
        DisposableEffect(model, activity) { onDispose { if (activity?.isChangingConfigurations != true) slot.release(model) } }
        val state by model.runner.state.collectAsState()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val lifecycleState by lifecycle.currentStateFlow.collectAsState()
        val currentOnSent by rememberUpdatedState(onSent)
        LaunchedEffect(model, state, lifecycleState, activity, generation) {
            // A retained upload owns no Activity/navigation callback. The new resumed host
            // consumes a confirmed result once, including completion while no host is resumed.
            if (lifecycleState == Lifecycle.State.RESUMED && activity?.isChangingConfigurations != true &&
                repository.isCurrentGeneration(generation) && model.consumeCompletion(generation)) currentOnSent()
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.select(context, it) }
        val editable = state == DeliveryState.Ready && model.editable()
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        // Preserve the existing HostedScreen route and keyboard avoidance; no synthetic modal backdrop.
        BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
            // A valid 16000×1 source must never create a million-dp lazy child. Fit extreme ratios
            // into a viewport-bounded slot; ordinary portrait/landscape widths remain 50/112.5dp.
            val maximumPreviewWidth = (maxWidth - 30.dp).coerceAtLeast(48.dp)
            Surface(Modifier.fillMaxWidth().testTag("composer-surface"),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("composer-scroll")) {
                    if (showTitle) Text(if (initial.replyId == null) "发布动态" else "回复",
                        Modifier.padding(horizontal = 15.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 10.dp)) {
                        BasicTextField(model.input, { model.edit(it) }, enabled = editable,
                            minLines = 4, maxLines = 8,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().testTag("composer-editor").semantics {
                                contentDescription = "内容"
                                if (model.characterCount > MAX_COMPOSER_CHARACTERS) error("内容超过500字")
                            },
                            decorationBox = { inner -> Box {
                                if (model.input.text.isEmpty()) Text("内容", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge)
                                inner()
                            } })
                        Text("${if (model.characterCount > MAX_COMPOSER_CHARACTERS) "500+" else model.characterCount.toString()}/500",
                            Modifier.align(Alignment.End).padding(top = 4.dp),
                            color = if (model.characterCount > MAX_COMPOSER_CHARACTERS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Unlike the source's fixed 52dp row, every action can reflow at compact widths/large text.
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("composer-toolbar"),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        TextButton(enabled = editable && model.images.size < MAX_FEED_IMAGES,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("composer-add-images"),
                            onClick = { if (model.editable()) picker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/gif")) }) {
                            Text("添加图片（${model.images.size}/9）")
                        }
                        Row(Modifier.heightIn(min = 48.dp).testTag("composer-audience")
                            .toggleable(value = model.option, enabled = editable, role = Role.Checkbox, onValueChange = model::updateOption)
                            .padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(model.option, onCheckedChange = null, enabled = editable, modifier = Modifier.size(48.dp))
                            Text(if (initial.replyId == null) "仅自己可见" else "回复并转发")
                        }
                        Button(enabled = editable && model.text.isNotBlank() && model.characterCount <= MAX_COMPOSER_CHARACTERS,
                            onClick = { focus.clearFocus(); keyboard?.hide(); model.prepare() },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("composer-review")) { Text("检查并发送") }
                    }
                    if (model.selecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (model.images.isNotEmpty()) LazyRow(
                        modifier = Modifier.fillMaxWidth().testTag("composer-images"),
                        contentPadding = PaddingValues(start = 15.dp, end = 15.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(model.images, key = { _, image -> image.name }) { index, image ->
                            val ratio = image.width.coerceAtLeast(1).toFloat() / image.height.coerceAtLeast(1)
                            val fittedSlotWidth = (75.dp * ratio).coerceIn(48.dp, maximumPreviewWidth)
                            val paintedWidth = minOf(fittedSlotWidth.value, 75f * ratio)
                            val paintedHeight = minOf(75f, fittedSlotWidth.value / ratio)
                            // Below a 16dp painted short axis, a truthful labeled placeholder is more
                            // useful than an apparently blank slot. This never changes the selected bytes.
                            val limitedPreview = minOf(paintedWidth, paintedHeight) < 16f
                            val previewWidth = if (limitedPreview) fittedSlotWidth.coerceAtLeast(minOf(144.dp, maximumPreviewWidth)) else fittedSlotWidth
                            Column(Modifier.width(previewWidth)) {
                                if (limitedPreview) {
                                    val label = if (ratio >= 1f) "超宽图预览受限" else "长图预览受限"
                                    Surface(Modifier.fillMaxWidth().heightIn(min = 75.dp).testTag("composer-image-${index + 1}")
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = "附件${index + 1}，${image.width}×${image.height}，$label，原图保持不变"
                                        }, color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(4.dp)) {
                                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center) {
                                            Text(label, style = MaterialTheme.typography.bodySmall)
                                            Text("${image.width}×${image.height}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                } else {
                                    // Local staged file only: preserve all four edges rather than square-cropping.
                                    AsyncImage(ImageRequest.Builder(context).data(image.file).memoryCachePolicy(CachePolicy.DISABLED)
                                        .diskCachePolicy(CachePolicy.DISABLED).build(), "附件${index + 1}，${image.width}×${image.height}",
                                        modifier = Modifier.width(previewWidth).height(75.dp).testTag("composer-image-${index + 1}"),
                                        contentScale = ContentScale.Fit)
                                }
                                IconButton(onClick = { model.remove(image) }, enabled = editable,
                                    modifier = Modifier.align(Alignment.CenterHorizontally).size(48.dp).testTag("composer-remove-${index + 1}")) {
                                    Icon(FlutterMaterialIcons.Close, "移除第${index + 1}张图片", Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("单张20MB、总计60MB；原图可能包含位置等元数据。选择图片不会上传。", style = MaterialTheme.typography.bodySmall)
                        if (initial.replyId == null && model.option && model.images.isNotEmpty())
                            Text("仅自己可见控制动态展示，不保证上传图片链接本身私密。", style = MaterialTheme.typography.bodySmall)
                        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        MutationStatus(state, model.runner, onChallengeRetry = model::retry) { model.runner.allowCorrection() }
                    }
                }
            }
        }
        model.pending?.let { frozen ->
            val audience = when {
                frozen.draft.replyId != null -> if (frozen.draft.replyAndForward) "发送回复并转发" else "发送回复"
                frozen.draft.selfOnly -> "发布仅自己可见的动态"
                else -> "公开发布动态"
            }
            // A separate Android Dialog owner replaces LocalDensity with its resource density.
            // Restore the live effective system × app density inside every content slot, exactly once.
            val confirmationDensity = LocalDensity.current
            AlertDialog(onDismissRequest = model::dismissConfirmation,
                title = { CompositionLocalProvider(LocalDensity provides confirmationDensity) { Text("确认发送？") } },
                text = { CompositionLocalProvider(LocalDensity provides confirmationDensity) {
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        Text("$audience，包含${frozen.images.size}张图片。确认后才会上传并发送。" +
                            (if (frozen.images.isNotEmpty()) "原图可能包含位置等元数据。" else "") +
                            if (frozen.draft.selfOnly && frozen.images.isNotEmpty()) "仅自己可见不保证图片链接私密。" else "")
                    }
                } },
                dismissButton = { CompositionLocalProvider(LocalDensity provides confirmationDensity) {
                    TextButton(onClick = model::dismissConfirmation, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回编辑") }
                } },
                confirmButton = { CompositionLocalProvider(LocalDensity provides confirmationDensity) {
                    TextButton(onClick = { model.confirm(repository) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("确认发送") }
                } })
        }
        }
    }
}
