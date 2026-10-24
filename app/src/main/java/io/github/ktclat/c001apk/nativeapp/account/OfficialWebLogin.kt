package io.github.ktclat.c001apk.nativeapp.account

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream

/** User-operated official site. No JavaScript bridges, imported cookies, automatic form filling,
 * proof substitution, or secret-bearing URLs. Unsupported isolation fails closed. */
@Composable
internal fun OfficialWebLogin(coordinator: LoginCoordinator, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val teardownScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    var attempt by remember { mutableStateOf<LoginCoordinator.OfficialAttempt?>(null) }
    var profile by remember { mutableStateOf<OfficialLoginProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var closing by remember { mutableStateOf(false) }
    fun fail(message: String) {
        if (error != null || closing) return
        error = message
        attempt?.let { coordinator.cancelOfficial(it) }
        scope.launch { if (profile?.cleanup() == false) error = "清理未确认完成，请退出应用后重试。未导入登录信息。" }
    }
    DisposableEffect(coordinator) {
        onDispose {
            attempt?.let { coordinator.cancelOfficial(it) }
            teardownScope.launch { try { profile?.cleanup() } finally { teardownScope.cancel() } }
        }
    }
    LaunchedEffect(coordinator) {
        try {
            if (!OfficialLoginProfile.supported()) {
                error = "当前系统 WebView 不支持安全隔离、完整 Cookie 属性或清理回执。请更新系统 WebView 后重试。"
                return@LaunchedEffect
            }
            val current = coordinator.beginOfficialLogin() ?: run { error = "登录会话已失效，请取消后重试。"; return@LaunchedEffect }
            attempt = current
            val isolated = OfficialLoginProfile.create(context)
            profile = isolated
            val nonce = current.gate.nonce
            isolated.view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage) = true // Never log page messages or secrets.
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message) = false
                override fun onShowFileChooser(webView: WebView, callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean { callback.onReceiveValue(null); return true }
            }
            isolated.view.setDownloadListener { _, _, _, _, _ -> fail("官方登录窗口不支持下载。") }
            isolated.view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    if (request.isForMainFrame) !OfficialWebLoginPolicy.allowsNavigation(request.url.toString()) else !OfficialWebLoginPolicy.allowsResource(request.url.toString())
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (OfficialWebLoginPolicy.allowsResource(request.url.toString())) null else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (!OfficialWebLoginPolicy.allowsNavigation(url)) { view.stopLoading(); fail("登录网页跳转到未允许的地址，已停止。") }
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, sslError: SslError) { handler.cancel(); fail("登录网页安全连接验证失败。") }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, webError: WebResourceError) { if (request.isForMainFrame) fail("官方登录网页加载失败，请取消后重试。") }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) { if (request.isForMainFrame && response.statusCode >= 400) fail("官方登录网页返回错误，未导入登录信息。") }
                override fun onPageFinished(view: WebView, url: String) {
                    if (closing || error != null || !current.gate.isActive() || !OfficialWebLoginPolicy.isCompletion(url) || view.url != url) return
                    scope.launch {
                        try {
                            if (!coordinator.officialCurrent(current)) { fail("账号已切换，本次网页登录已取消。"); return@launch }
                            if (!current.gate.beginCompletion(nonce, url, isolated.cookieInfo(), System.currentTimeMillis())) { fail("官方网页未返回完整且作用域正确的登录信息。"); return@launch }
                            closing = true
                            val cleaned = isolated.cleanup()
                            if (!coordinator.finishOfficialLogin(current, cleaned)) {
                                error = if (cleaned) "登录会话已失效，未导入登录信息。" else "清理未确认完成，请退出应用后重试。未导入登录信息。"
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { current.gate.cancel(); error = "登录处理失败，未确认登录成功。"; isolated.cleanup() }
                    }
                }
            }
            isolated.view.loadUrl(OfficialWebLoginPolicy.LOGIN)
            while (current.gate.isActive()) {
                delay(1_000)
                if (!coordinator.officialCurrent(current)) { fail("账号已切换，本次网页登录已取消。"); break }
            }
            if (!closing && error == null) fail("网页登录已超时或取消，请重新开始。")
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { fail("无法创建独立登录环境，请退出应用或更新系统 WebView 后重试。") }
    }
    Column(modifier.fillMaxSize().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("酷安官方网页登录", style = MaterialTheme.typography.titleLarge)
        Text("account.coolapk.com", style = MaterialTheme.typography.labelLarge)
        Text("请亲自完成登录和验证。独立 WebView 配置可能临时写入磁盘；结束后等待清理，再加密保存必要登录信息。清理不等同于物理不可恢复擦除。", style = MaterialTheme.typography.bodySmall)
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        else if (closing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在清理网页登录环境…") }
        else profile?.let { isolated -> key(isolated) { AndroidView(factory = { isolated.view }, modifier = Modifier.weight(1f).fillMaxWidth()) } }
            ?: CircularProgressIndicator()
        TextButton(onClick = { attempt?.let { coordinator.cancelOfficial(it) }; onCancel() }) { Text("取消并关闭") }
    }
}
