// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import coil.compose.AsyncImage
import io.github.ktclat.c001apk.nativeapp.ui.FlutterMaterialIcons

/** A failed public avatar/icon is an explicit failure, never an empty or successful image. */
@Composable
internal fun PublicImage(
    url: String?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    avatar: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val loader = LocalMediaImageLoader.current ?: remember(context) { publicMediaImageLoader(context) }
    var phase by remember(url, loader) { mutableIntStateOf(0) }
    val label = if (avatar) "头像" else "图片"
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).semantics {
        stateDescription = when {
            url.isNullOrBlank() -> "${label}未提供"
            phase == 1 -> "${label}已加载"
            phase == 2 -> "${label}加载失败"
            else -> "${label}加载中"
        }
        if (phase == 2) this.contentDescription = "${label}加载失败"
    }, contentAlignment = Alignment.Center) {
        if (phase != 1) Icon(
            when { phase == 2 -> FlutterMaterialIcons.ErrorOutline; avatar -> FlutterMaterialIcons.Person; else -> FlutterMaterialIcons.Image },
            null, Modifier.fillMaxWidth(.62f), tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!url.isNullOrBlank() && phase != 2) AsyncImage(url, contentDescription,
            imageLoader = loader, contentScale = contentScale, modifier = Modifier.fillMaxSize(),
            onSuccess = { phase = 1 }, onError = { phase = 2 })
    }
}
