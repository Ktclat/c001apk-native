// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.net.URI

fun isSafePublicWebUrl(raw: String): Boolean = runCatching {
    val uri = URI(raw)
    uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.port in setOf(-1, 443)
}.getOrDefault(false)

/** Explicit link gesture only. API26/27 cannot isolate WebView data directories: use system browser. */
fun openWebLink(context: Context, url: String, preferExternal: Boolean) {
    val uri = URI(url)
    require(uri.scheme in setOf("https", "http") && uri.host != null && uri.rawUserInfo == null)
    if (!preferExternal && Build.VERSION.SDK_INT >= 28 && isSafePublicWebUrl(url)) {
        context.startActivity(Intent(context, PublicWebActivity::class.java).putExtra("url", url))
    } else {
        if (!preferExternal) android.widget.Toast.makeText(context, "此链接使用系统浏览器打开，以保持网页与账号数据隔离", android.widget.Toast.LENGTH_LONG).show()
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/** Manifest MUST run this unexported Activity in :public_web. No account store is accessed. */
class PublicWebActivity : ComponentActivity() {
    companion object { private var directoryConfigured = false }
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT < 28) { super.onCreate(savedInstanceState); finish(); return }
        val initial = intent.getStringExtra("url").orEmpty()
        if (!isSafePublicWebUrl(initial)) { super.onCreate(savedInstanceState); finish(); return }
        val isolated = runCatching {
            check(android.app.Application.getProcessName().endsWith(":public_web"))
            if (!directoryConfigured) { WebView.setDataDirectorySuffix("public-web"); directoryConfigured = true }
            // Service-worker fetches do not pass through WebViewClient interception.
            ServiceWorkerController.getInstance().apply {
                serviceWorkerWebSettings.allowFileAccess = false
                serviceWorkerWebSettings.allowContentAccess = false
                setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                        if (isSafePublicWebUrl(request.url.toString())) null
                        else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                })
            }
        }.isSuccess
        super.onCreate(savedInstanceState)
        if (!isolated) { runCatching { openWebLink(this, initial, true) }; finish(); return }
        setContent { MaterialTheme { PublicWebScreen(initial, ::finish) } }
    }
}

@androidx.annotation.RequiresApi(28)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublicWebScreen(initial: String, close: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var current by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    BackHandler { if (web?.canGoBack() == true) web?.goBack() else close() }
    DisposableEffect(Unit) { onDispose { web?.let { it.stopLoading(); it.destroy() }; web = null } }
    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text(runCatching { URI(current).host }.getOrDefault("网页")) }, navigationIcon = { TextButton(onClick = { if (web?.canGoBack() == true) web?.goBack() else close() }) { Text("返回") } }, actions = { TextButton(onClick = close) { Text("关闭") } })
            Text(current, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Text("独立网页，不同步应用账号；部分网页需用系统浏览器", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { error = null; web?.reload() }) { Text("重新加载") }
                TextButton(onClick = { web?.context?.let { context -> try { openWebLink(context, current, true) } catch (_: Exception) { error = "找不到可打开链接的浏览器" } } }) { Text("系统浏览器打开") }
            }
            Row {
                TextButton(onClick = { web?.context?.let { context -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("网页链接", current)) } }) { Text("复制链接") }
                TextButton(onClick = { web?.clearCache(true); web?.clearHistory() }) { Text("清理网页缓存") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        }
    }) { padding ->
        AndroidView(modifier = Modifier.fillMaxSize().padding(padding), factory = { context ->
            WebView(context).apply {
                web = this
                // Standard web JavaScript stays within this isolated renderer; no native bridge exists.
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(true)
                settings.mediaPlaybackRequiresUserGesture = true
                settings.setGeolocationEnabled(false)
                settings.safeBrowsingEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                setDownloadListener { _, _, _, _, _ -> error = "网页下载不会自动开始，请使用系统浏览器" }
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean = false
                    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
                    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
                    override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean { result.cancel(); return true }
                    override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean { callback.onReceiveValue(null); return true }
                    override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val blocked = !isSafePublicWebUrl(request.url.toString())
                        if (blocked && request.isForMainFrame) error = "已阻止不安全或不支持的网页跳转"
                        return blocked
                    }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (isSafePublicWebUrl(request.url.toString())) null else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { current = url; loading = true; error = null }
                    override fun onPageFinished(view: WebView, url: String) { current = url; loading = false }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) { if (request.isForMainFrame) { loading = false; error = "网页加载失败，可重试或使用系统浏览器" } }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) { if (request.isForMainFrame) { loading = false; error = "网页请求失败 (${response.statusCode})" } }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: android.net.http.SslError) { handler.cancel(); loading = false; error = "网页证书异常，已停止加载" }
                    override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) { callback.backToSafety(true); error = "此网页被安全浏览阻止" }
                }
                loadUrl(initial)
            }
        })
    }
}
