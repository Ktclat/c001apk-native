package io.github.ktclat.c001apk.nativeapp.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Narrow, single-use proof receiver. It exposes no storage, cookies, URLs or app functions. */
internal class ChallengeGate(val challenge: HumanChallengeSpec, private val clock: () -> Long = android.os.SystemClock::elapsedRealtime) {
    val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val expiresAt = clock() + 5 * 60_000
    private val finished = AtomicBoolean(false)
    fun accept(nonce: String, raw: String): String? {
        if (nonce != this.nonce || clock() > expiresAt || raw.length !in 1..8192) return null
        val proof = "NEC:${challenge.captchaId.take(8)}:$raw"
        if (!challenge.accepts(proof) || !finished.compareAndSet(false, true)) return null
        return proof
    }
    fun cancel() { finished.set(true) }
}

internal object ChallengeOriginPolicy {
    const val PAGE = "https://captcha.c001apk.invalid/"
    fun resourceAllowed(raw: String): Boolean {
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return false
        val host = uri.host ?: return false
        return listOf("126.net", "127.net", "163.com", "163yun.com").any { host == it || host.endsWith(".$it") }
    }
}

/** Legitimate visible provider SDK. Human interaction is required; there is no solver or proof fabrication.
 * No account cookies are injected. Main-frame navigation, frames, files, downloads and popups are denied.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HumanVerification(challenge: HumanChallengeSpec, onProof: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val gate = remember(challenge) { ChallengeGate(challenge) }
    val currentProof by rememberUpdatedState(onProof)
    var view by remember(challenge) { mutableStateOf<WebView?>(null) }
    var error by remember(challenge) { mutableStateOf(false) }
    DisposableEffect(gate) { onDispose { gate.cancel(); view?.apply { stopLoading(); removeJavascriptInterface("CaptchaProof"); destroy() }; view = null } }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("人机验证", style = MaterialTheme.typography.headlineSmall)
        Text("请亲自完成下方服务商验证。验证结果只用于本次请求；取消不会重试登录。")
        if (error) Text("验证组件加载失败。可取消后重新准备登录，不会绕过验证。", color = MaterialTheme.colorScheme.error)
        key(challenge) { AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
            WebView(context).apply {
                view = this
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.domStorageEnabled = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                setDownloadListener { _, _, _, _, _ -> }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, false, false) }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (!request.isForMainFrame && ChallengeOriginPolicy.resourceAllowed(request.url.toString())) null
                        else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel() }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) { if (request.isForMainFrame) error = true }
                }
                val webView = this
                addJavascriptInterface(object {
                    @JavascriptInterface fun verified(nonce: String?, raw: String?) {
                        if (nonce == null || raw == null) return
                        Handler(Looper.getMainLooper()).post {
                            if (webView.url != ChallengeOriginPolicy.PAGE) return@post
                            val accepted = gate.accept(nonce, raw) ?: return@post
                            currentProof(accepted)
                        }
                    }
                }, "CaptchaProof")
                loadDataWithBaseURL(ChallengeOriginPolicy.PAGE, challengeHtml(challenge, gate.nonce), "text/html", "UTF-8", null)
            }
        }) }
        TextButton(onClick = { gate.cancel(); onCancel() }) { Text("取消验证") }
    }
}

private fun challengeHtml(challenge: HumanChallengeSpec, nonce: String): String = """<!doctype html><html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-$nonce' https://*.126.net https://*.127.net https://*.163.com https://*.163yun.com; connect-src https://*.126.net https://*.127.net https://*.163.com https://*.163yun.com; img-src data: https://*.126.net https://*.127.net https://*.163.com https://*.163yun.com; style-src 'unsafe-inline' https://*.126.net https://*.127.net; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
<style>body{font:16px sans-serif;padding:12px}#captcha{margin-top:24px}</style></head><body><p id="status">正在加载验证组件…</p><div id="captcha"></div>
<script nonce="$nonce">
function statusText(s){document.getElementById('status').textContent=s;}
var timeout=setTimeout(function(){statusText('验证组件加载超时，请取消后重试');},15000);
var script=document.createElement('script');script.src='https://cstaticdun.126.net/load.min.js';
script.onerror=function(){clearTimeout(timeout);statusText('验证组件加载失败，请取消后重试');};
script.onload=function(){try{initNECaptcha({captchaId:'${challenge.captchaId}',element:'#captcha',protocol:'https',mode:'embed',apiVersion:2,width:Math.min(320,window.innerWidth-48)+'px',lang:'zh-CN',onVerify:function(err,data){if(!err&&data&&typeof data.validate==='string'){CaptchaProof.verified('$nonce',data.validate);}else{statusText('验证未通过，请按提示重试');}}},function(){clearTimeout(timeout);statusText('请按下方提示完成验证');},function(){clearTimeout(timeout);statusText('验证组件初始化失败');});}catch(e){clearTimeout(timeout);statusText('验证组件不可用，请取消后重试');}};document.head.appendChild(script);
</script></body></html>"""
