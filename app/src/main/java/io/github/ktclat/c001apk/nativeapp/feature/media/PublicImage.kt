// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import io.github.ktclat.c001apk.nativeapp.ui.FlutterMaterialIcons

/** Only opted-in large banners get an action; icons and avatars never intercept parent navigation. */
@Composable
internal fun PublicImage(
    url: String?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    avatar: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop,
    allowRetry: Boolean = false,
) {
    val context = LocalContext.current
    val loader = LocalMediaImageLoader.current ?: remember(context) { publicMediaImageLoader(context) }
    val owner = remember(url, loader) { PublicImageRequestState() }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    val phase = owner.phase
    val attempt = owner.attempt
    val label = if (avatar) "头像" else "图片"
    val request = remember(url, context, owner, attempt) {
        ImageRequest.Builder(context).data(url).apply {
            if (attempt > 0) {
                memoryCachePolicy(CachePolicy.WRITE_ONLY)
                diskCachePolicy(CachePolicy.WRITE_ONLY)
            }
        }.build()
    }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant).semantics {
        stateDescription = when {
            url.isNullOrBlank() -> "${label}未提供"
            phase == MediaImagePhase.Success -> "${label}已加载"
            phase == MediaImagePhase.Error -> "${label}加载失败"
            else -> "${label}加载中"
        }
        if (phase == MediaImagePhase.Error) this.contentDescription = "${label}加载失败"
    }, contentAlignment = Alignment.Center) {
        val showRecovery = allowRetry && !avatar && publicImageRetryFits(maxWidth.value, maxHeight.value, fontScale)
        if (phase == MediaImagePhase.Error && showRecovery) {
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(owner.failure?.displayText().orEmpty(), fontSize = 12.sp, lineHeight = 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("public-image-failure-reason"))
                if (owner.canRetry) TextButton(onClick = owner::retry,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("public-image-retry")) {
                    Text("重试一次", fontSize = 14.sp, lineHeight = 20.sp)
                } else Text("已重试", fontSize = 14.sp, lineHeight = 20.sp)
            }
        } else if (phase != MediaImagePhase.Success) Icon(
            when { phase == MediaImagePhase.Error -> FlutterMaterialIcons.ErrorOutline; avatar -> FlutterMaterialIcons.Person; else -> FlutterMaterialIcons.Image },
            null, Modifier.fillMaxWidth(.62f), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!url.isNullOrBlank() && phase != MediaImagePhase.Error) key(owner, attempt) {
            AsyncImage(request, contentDescription, imageLoader = loader, contentScale = contentScale,
                modifier = Modifier.fillMaxSize(), onSuccess = { owner.finish(attempt) },
                onError = { owner.finish(attempt, imageLoadFailure(it.result.throwable)) })
        }
    }
}
