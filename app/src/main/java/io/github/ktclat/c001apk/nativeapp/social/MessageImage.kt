// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.compose.AsyncImage
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun safeMessageImage(raw: String): String? = raw.toHttpUrlOrNull()?.takeIf {
    it.isHttps && it.port == 443 && it.host == "image.coolapk.com" && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
}?.toString()
private object AnonymousImages {
    @Volatile private var loader: ImageLoader? = null
    @Synchronized fun loader(context: Context): ImageLoader = loader ?: ImageLoader.Builder(context.applicationContext)
        .okHttpClient { OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build() }
        .build().also { loader = it }
}
@Composable
internal fun MessageImage(repository: SocialRepository, row: SocialRow) {
    val context = LocalContext.current.applicationContext
    val loader = remember(context) { AnonymousImages.loader(context) }
    var location by remember(row.id) { mutableStateOf(row.imageUrl?.let(::safeMessageImage)) }
    var loading by remember(row.id) { mutableStateOf(false) }
    var error by remember(row.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun resolveImage() {
        loading = true; error = null; location = null
        try { when (val result = repository.imageLocation(row.id)) {
            is ApiResult.Success -> { location = safeMessageImage(result.value); if (location == null) error = "图片地址不符合安全限制" }
            is ApiResult.Failure -> error = result.message
        } } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "无法读取图片地址，请稍后重试" }
        finally { loading = false }
    }
    Column {
        val url = location
        if (url != null) AsyncImage(model = ImageRequest.Builder(context).data(url).memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build(), imageLoader = loader, contentDescription = "私信图片",
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp),
            onError = { error = "图片加载失败，请稍后重试" })
        else TextButton(enabled = !loading, onClick = { scope.launch {
            resolveImage()
        } }) { Text(if (loading) "正在解析图片" else "查看图片") }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(enabled = !loading, onClick = { scope.launch { resolveImage() } }) { Text("重新解析图片") }
        }
    }
}
