package io.github.ktclat.c001apk.nativeapp.account

import io.github.ktclat.c001apk.nativeapp.data.RequestSession

/** Opt-in; public fallback and third-party clients must never install this adapter. */
fun AccountStore.captureRequestSession(): RequestSession {
    val captured = snapshot()
    return requestSessionFor(captured)
}

/** Pin all stages of a social operation to its original identity. */
fun AccountStore.requestSessionFor(captured: SessionSnapshot): RequestSession {
    return RequestSession(diagnosticGeneration = captured.generation,
        diagnosticMode = if (captured.accountId == null) io.github.ktclat.c001apk.nativeapp.diagnostics.HomeRequestMode.GUEST else io.github.ktclat.c001apk.nativeapp.diagnostics.HomeRequestMode.AUTHENTICATED,
        isGuest = captured.accountId == null, cookieFor = { url -> captured.cookieHeader(url).takeIf { it.isNotEmpty() } },
        isCurrent = { isCurrent(captured) }, acceptCookies = { url, headers -> acceptCookies(captured, url, headers); Unit }, deviceCode = captured.deviceId,
        isReadSessionCurrent = { isCurrentRead(captured) }, readOwner = captured.owner,
        readSessionCookies = { captured.cookies.filter { it.name == "SESSID" } },
        isSessionCurrent = { val current = snapshot(); captured.revision == current.revision && captured.sameIdentityAndSession(current) },
        acceptChallengeCookies = { url, headers -> acceptChallengeCookies(captured, url, headers)?.let(::requestSessionFor) })
}
