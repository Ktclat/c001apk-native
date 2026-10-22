package io.github.ktclat.c001apk.nativeapp.account

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.CookieManagerCompat
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Per-attempt disk-backed profile, NOT incognito. Website data may temporarily exist on disk.
 * Successful scoped deletion is awaited, but physical profile deletion may be asynchronous and
 * is not a promise of forensic erasure. No default CookieManager or shared profile fallback. */
internal class OfficialLoginProfile private constructor(val view: WebView, private val profile: Profile,
    private val name: String) {
    private var cleanupResult: CompletableDeferred<Boolean>? = null
    fun cookieInfo(): List<String> {
        check(cleanupResult == null)
        return CookieManagerCompat.getCookieInfo(profile.cookieManager, OfficialWebLoginPolicy.COMPLETE)
    }
    suspend fun cleanup(): Boolean = withContext(NonCancellable + Dispatchers.Main.immediate) {
        val result = cleanupResult ?: CompletableDeferred<Boolean>().also { pending ->
            cleanupResult = pending
            var teardownOk = true
            fun attempt(block: () -> Unit) { try { block() } catch (_: Exception) { teardownOk = false } }
            // Each stage is independent: one provider exception must never skip destruction.
            attempt { profile.serviceWorkerController.serviceWorkerWebSettings.blockNetworkLoads = true }
            attempt { view.webViewClient = WebViewClient() }
            attempt { view.stopLoading() }
            attempt { (view.parent as? ViewGroup)?.removeView(view) }
            attempt { view.destroy() }
            try {
                WebStorageCompat.deleteBrowsingData(profile.webStorage) {
                    // Nothing may write after confirmed teardown; deletion alone is not atomic.
                    if (teardownOk) clearedProfiles.add(name)
                    try {
                        ProfileStore.getInstance().deleteProfile(name)
                        clearedProfiles.remove(name)
                    } catch (_: Exception) { /* Remove unloaded profile next process. */ }
                    pending.complete(teardownOk)
                }
            } catch (_: Exception) { pending.complete(false) }
        }
        withTimeoutOrNull(10_000) { result.await() } ?: false
    }
    companion object {
        private const val PREFIX = "c001apk.login."
        // Main-thread confined, no credentials. Loaded empty profiles may require process restart to remove.
        private val clearedProfiles = mutableSetOf<String>()
        fun supported(): Boolean = listOf(WebViewFeature.MULTI_PROFILE, WebViewFeature.DELETE_BROWSING_DATA,
            WebViewFeature.GET_COOKIE_INFO).all(WebViewFeature::isFeatureSupported)
        @SuppressLint("SetJavaScriptEnabled")
        fun create(context: Context): OfficialLoginProfile {
            check(supported()) { "Isolated login is unsupported by this WebView" }
            val store = ProfileStore.getInstance()
            val orphans = store.allProfileNames.filter { it.startsWith(PREFIX) }
            // Never load an orphan profile. Deletion removes it logically and may delete files later.
            for (old in orphans) {
                try { store.deleteProfile(old); clearedProfiles.remove(old) }
                catch (_: Exception) { check(old in clearedProfiles) { "Previous login cleanup needs a restart" } }
            }
            check(store.allProfileNames.count { it.startsWith(PREFIX) } < 8) { "Restart to finish clearing old login profiles" }
            val name = PREFIX + UUID.randomUUID()
            WebView.setWebContentsDebuggingEnabled(false)
            val view = WebView(context)
            try {
                // Must precede settings, cookie access, navigation, or any other WebView operation.
                WebViewCompat.setProfile(view, name)
                val profile = WebViewCompat.getProfile(view)
                profile.serviceWorkerController.serviceWorkerWebSettings.blockNetworkLoads = true
                profile.cookieManager.setAcceptThirdPartyCookies(view, false)
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mediaPlaybackRequiresUserGesture = true
                }
                return OfficialLoginProfile(view, profile, name)
            } catch (failure: Exception) {
                runCatching { view.destroy() }
                runCatching { store.deleteProfile(name) }
                throw failure
            }
        }
    }
}
