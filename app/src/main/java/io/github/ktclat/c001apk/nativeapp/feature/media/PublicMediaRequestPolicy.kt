// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.feature.media

import android.os.Build
import io.github.ktclat.c001apk.nativeapp.data.ClientDeviceProfile
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Flutter's global HttpClient UA uses these ordinary OS/model fields, not its API device code. */
internal fun publicMediaSystemProfile(): ClientDeviceProfile? = ClientDeviceProfile.fromSystemProperties(
    Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty(), Build.MODEL.orEmpty(),
    Build.DISPLAY.orEmpty(), Build.VERSION.RELEASE.orEmpty(),
)

internal val anonymousMediaUserAgent: String get() = "okhttp/${OkHttp.VERSION}"
private val publicMediaOrigins = setOf("image.coolapk.com", "avatar.coolapk.com", "static.coolapk.com")

/** No account/preferences/API transport is consulted. Invalid system fields keep OkHttp's UA. */
internal class PublicMediaRequestPolicy(profile: ClientDeviceProfile?) : Interceptor {
    private val appUserAgent = profile?.userAgent()?.takeIf { value ->
        value.length <= 4096 && value.all { it in ' '..'~' }
    }

    internal fun userAgent(initial: HttpUrl, current: HttpUrl): String =
        if (appUserAgent != null && initial.isHttps && current.isHttps && initial.port == 443 && current.port == 443 &&
            initial.host == current.host && current.host in publicMediaOrigins &&
            initial.username.isEmpty() && initial.password.isEmpty() && current.username.isEmpty() && current.password.isEmpty())
            appUserAgent else anonymousMediaUserAgent

    override fun intercept(chain: Interceptor.Chain): Response {
        // A network interceptor runs on *each* redirect hop, after the new origin is known.
        // Do not disclose model/build information to another host, even another allowed CDN.
        val request = chain.request()
        return chain.proceed(anonymousMediaRequest(request, userAgent(chain.call().request().url, request.url)))
    }
}

/** Strip sensitive/caller-supplied API headers before proxies, caches and redirects see them. */
internal object AnonymousMediaHeaders : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(anonymousMediaRequest(chain.request()))
}

private fun anonymousMediaRequest(request: Request, userAgent: String = anonymousMediaUserAgent): Request {
    val builder = request.newBuilder()
    for (name in request.headers.names()) {
        if (name.startsWith("X-App-", ignoreCase = true) || name.startsWith("X-Sdk-", ignoreCase = true) || name.lowercase() in setOf(
                "cookie", "cookie2", "set-cookie", "authorization", "proxy-authorization", "referer", "x-requested-with")) builder.removeHeader(name)
    }
    return builder.header("User-Agent", userAgent).build()
}

/** Shared anonymous protocol only; callers retain their distinct DNS/proxy/cache/size policies. */
internal fun anonymousMediaClientBuilder(profile: ClientDeviceProfile? = publicMediaSystemProfile()): OkHttpClient.Builder =
    OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).followSslRedirects(false)
        .addInterceptor(AnonymousMediaHeaders).addNetworkInterceptor(PublicMediaRequestPolicy(profile))
