// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Opens the existing human-operated challenge host only after an explicit button press. */
@Composable internal fun DetailReadVerification(pending: PendingDetailRead, loading: Boolean, retry: () -> Unit) {
    ReadVerification(pending, loading, "detail", "验证完成，重新读取正文", retry)
}

@Composable internal fun PageReadVerification(pending: PendingPageRead, loading: Boolean, retry: () -> Unit) {
    ReadVerification(pending, loading, "read", "验证完成，重新读取内容", retry)
}

@Composable internal fun PersonalReadVerification(pending: PendingPersonalRead, loading: Boolean, retry: () -> Unit) {
    ReadVerification(pending, loading, "personal", "验证完成，重新读取列表", retry)
}

@Composable internal fun CountsReadVerification(pending: PendingCountsRead, loading: Boolean, retry: () -> Unit) {
    ReadVerification(pending, loading, "counts", "验证完成，重新读取未读数量", retry)
}

/** Shared visible host; proof completion and the one read retry remain separate gestures. */
@Composable private fun ReadVerification(pending: PendingRead<*>, loading: Boolean, tag: String, retryLabel: String, retry: () -> Unit) {
    val state by pending.state.collectAsState()
    var show by remember(pending) { mutableStateOf(false) }
    var bytes by remember(pending) { mutableStateOf<ByteArray?>(null) }
    var error by remember(pending) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(pending) { onDispose { if (show) pending.cancel(); bytes?.fill(0) } }
    LaunchedEffect(state) { if (state == DetailVerificationState.CLOSED) show = false }
    when (state) {
        DetailVerificationState.AWAITING_HUMAN -> TextButton(enabled = !loading, onClick = { if (pending.canOpen()) show = true }, modifier = Modifier.testTag("$tag-human-verify")) { Text("亲自完成验证") }
        DetailVerificationState.LOADING -> LinearProgressIndicator(Modifier.fillMaxWidth())
        DetailVerificationState.VERIFIED -> Button(enabled = !loading, onClick = retry, modifier = Modifier.testTag("$tag-verified-retry")) { Text(retryLabel) }
        DetailVerificationState.CLOSED -> if (!loading) Text("验证已关闭或失效，可重新加载内容。", style = MaterialTheme.typography.bodySmall)
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (show) {
        LaunchedEffect(pending) {
            if (pending.challenge is ApiChallenge.Graphic) {
                when (val response = pending.image()) {
                    is ApiResult.Success -> bytes = response.value
                    is ApiResult.Failure -> { error = response.message; show = false }
                }
            }
        }
        Dialog(onDismissRequest = { pending.cancel(); show = false; bytes?.fill(0); bytes = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                when (val challenge = pending.challenge) {
                    is ApiChallenge.Graphic -> {
                        val image = bytes
                        if (image == null || state == DetailVerificationState.LOADING) Column(Modifier.padding(24.dp)) {
                            Text("正在准备验证"); LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = { pending.cancel(); show = false }) { Text("取消") }
                        } else GraphicCaptchaPrompt(image, onSubmit = { answer -> scope.launch {
                            try { if (!pending.verifyGraphic(answer)) error = "验证未通过，内容尚未重新读取" }
                            catch (cancelled: CancellationException) { throw cancelled }
                            finally { show = false; bytes?.fill(0); bytes = null }
                        } }, onCancel = { pending.cancel(); show = false; bytes?.fill(0); bytes = null })
                    }
                    is ApiChallenge.Netease -> {
                        val spec = remember(pending) { HumanChallengeSpec(challenge.captchaId, challenge.field) }
                        HumanVerification(spec,
                        onProof = { proof -> if (!pending.acceptHumanProof(proof)) error = "验证已失效，请重新加载内容"; show = false },
                        onCancel = { pending.cancel(); show = false },
                        explanation = "请亲自完成下方服务商验证。返回后仍需点击重新读取；通过后仅在内存中短时用于当前身份的读取，不会登录或执行账号操作。")
                    }
                }
            }
        }
    }
}
