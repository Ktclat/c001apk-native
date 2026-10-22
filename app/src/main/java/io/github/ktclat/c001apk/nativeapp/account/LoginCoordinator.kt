package io.github.ktclat.c001apk.nativeapp.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cookie

/** Transport must use an isolated ephemeral login cookie jar, never overwrite the active account. */
interface LoginTransport {
    suspend fun prepare(): LoginPreparation
    suspend fun submit(preparation: LoginPreparation, login: String, password: CharArray, captcha: String, proof: String?): LoginReply
    fun clear()
}
class LoginPreparation(val requestHash: String, val randomNumber: String) {
    init { require(requestHash.isNotBlank() && requestHash.length <= 4096 && randomNumber.isNotBlank()) }
    override fun toString() = "LoginPreparation(redacted)"
}
sealed interface LoginReply {
    class Authenticated(val id: String, val name: String, val cookies: List<Cookie>) : LoginReply
    class GraphicCaptcha : LoginReply
    class HumanChallenge(val challenge: HumanChallengeSpec) : LoginReply
    class WebLoginRequired : LoginReply
    class Rejected : LoginReply
}
class HumanChallengeSpec(val captchaId: String, val field: String) {
    init {
        require(captchaId.matches(Regex("[A-Za-z0-9]{16,64}")))
        require(field.isEmpty() || field.matches(Regex("_[A-Za-z0-9_]{1,63}")))
    }
    fun accepts(proof: String): Boolean = proof.startsWith("NEC:${captchaId.take(8)}:") &&
        proof.length in 14..8205 && proof.none { it.isWhitespace() || it.code < 32 || it.code >= 127 || it in ";,\"\\" }
}
sealed interface LoginState {
    data object Idle : LoginState
    data object Preparing : LoginState
    data object Ready : LoginState
    data object Submitting : LoginState
    data object GraphicCaptchaRequired : LoginState
    class HumanVerificationRequired(val challenge: HumanChallengeSpec) : LoginState
    data object WebLoginRequired : LoginState
    data object Rejected : LoginState
    data object Failed : LoginState
    data object Completed : LoginState
    data object Cancelled : LoginState
}

/** UI-thread confined. Password is never stored in state; caller must clear its text field too.
 * No automatic retry: after a human challenge the user explicitly submits again, at most once.
 * Transport is a contract only; no real login network implementation is enabled by this class.
 */
class LoginCoordinator(private val store: AccountStore, private val transport: LoginTransport, private val deviceId: String) {
    private val mutableState = MutableStateFlow<LoginState>(LoginState.Idle)
    val state = mutableState.asStateFlow()
    private var operation = 0L
    private var submitting = false
    private var preparation: LoginPreparation? = null
    private var captured: SessionSnapshot? = null
    private var challenge: HumanChallengeSpec? = null
    private var challengeRetried = false
    private var challengeCreatedNanos = 0L
    suspend fun prepare() {
        cancel(); val op = operation
        mutableState.value = LoginState.Preparing
        try {
            val session = withContext(Dispatchers.IO) { store.snapshot() }
            if (op != operation) return
            captured = session
            val result = transport.prepare()
            if (op != operation) return
            if (!withContext(Dispatchers.IO) { store.isCurrent(session) }) { if (op == operation) cancel(); return }
            if (op != operation) return
            preparation = result; mutableState.value = LoginState.Ready
        } catch (cancel: CancellationException) { if (op == operation) cancel(); throw cancel }
        catch (_: Exception) { if (op == operation) mutableState.value = LoginState.Failed }
    }
    suspend fun submit(login: String, password: CharArray, graphicCaptcha: String = "", humanProof: String? = null) {
        val op = operation
        var ownsSubmission = false
        try {
            if (submitting) return
            submitting = true
            ownsSubmission = true
            val prep = preparation ?: return
            val snapshot = captured ?: return
            if (mutableState.value == LoginState.Submitting) return
            if (!withContext(Dispatchers.IO) { store.isCurrent(snapshot) }) { if (op == operation) cancel(); return }
            if (op != operation) return
            val currentChallenge = challenge
            if (currentChallenge != null) {
                if (challengeRetried || humanProof == null || !currentChallenge.accepts(humanProof)) return
                if (System.nanoTime() - challengeCreatedNanos > 300_000_000_000L) { mutableState.value = LoginState.Rejected; return }
                challengeRetried = true
            } else if (humanProof != null) return
            require(login.isNotBlank() && password.isNotEmpty())
            mutableState.value = LoginState.Submitting
            val result = transport.submit(prep, login, password, graphicCaptcha, humanProof)
            if (op != operation) return
            if (!withContext(Dispatchers.IO) { store.isCurrent(snapshot) }) { if (op == operation) cancel(); return }
            if (op != operation) return
            mutableState.value = when (result) {
                is LoginReply.Authenticated -> {
                    val saved = withContext(Dispatchers.IO) { store.saveAuthenticated(result.id, result.name, deviceId, result.cookies, snapshot) }
                    if (op != operation) return
                    if (saved) {
                        preparation = null; transport.clear(); LoginState.Completed
                    } else LoginState.Cancelled
                }
                is LoginReply.GraphicCaptcha -> LoginState.GraphicCaptchaRequired
                is LoginReply.HumanChallenge -> if (challengeRetried) LoginState.Rejected else {
                    challenge = result.challenge; challengeCreatedNanos = System.nanoTime(); LoginState.HumanVerificationRequired(result.challenge)
                }
                is LoginReply.WebLoginRequired -> { preparation = null; transport.clear(); LoginState.WebLoginRequired }
                is LoginReply.Rejected -> LoginState.Rejected
            }
        } catch (cancel: CancellationException) { if (op == operation) cancel(); throw cancel }
        catch (_: Exception) { if (op == operation) mutableState.value = LoginState.Failed }
        finally { if (ownsSubmission && op == operation) submitting = false; password.fill('\u0000') }
    }
    fun cancel() {
        operation++; submitting = false; preparation = null; captured = null; challenge = null; challengeRetried = false
        transport.clear(); mutableState.value = LoginState.Cancelled
    }
}
