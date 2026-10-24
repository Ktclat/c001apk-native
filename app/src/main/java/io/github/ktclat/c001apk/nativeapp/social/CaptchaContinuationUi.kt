// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ktclat.c001apk.nativeapp.account.GraphicCaptchaPrompt
import io.github.ktclat.c001apk.nativeapp.account.HumanVerification
import io.github.ktclat.c001apk.nativeapp.domain.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Verification and retry are separate explicit gestures. This UI never solves a challenge. */
@Composable
internal fun CaptchaContinuationUi(pending: PendingCaptcha, runner: MutationRunner, onRetry: () -> Unit) {
    val progress by pending.progress.collectAsState()
    var show by remember(pending) { mutableStateOf(false) }
    var image by remember(pending) { mutableStateOf<ByteArray?>(null) }
    var error by remember(pending) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(pending) { onDispose { if (pending.progress.value != CaptchaProgress.CONSUMED) pending.cancel(); image?.fill(0) } }
    when (progress) {
        CaptchaProgress.VERIFIED -> Button(onClick = onRetry) { Text("重试原操作一次") }
        CaptchaProgress.AWAITING_HUMAN -> TextButton(onClick = { show = true }) { Text("亲自完成验证") }
        CaptchaProgress.LOADING -> LinearProgressIndicator(Modifier.fillMaxWidth())
        CaptchaProgress.CANCELLED, CaptchaProgress.EXPIRED, CaptchaProgress.REJECTED -> Text("验证已取消、失效或未通过；原操作没有重发")
        CaptchaProgress.CONSUMED -> Unit
    }
    if (progress != CaptchaProgress.CONSUMED) TextButton(onClick = { runner.abandonChallenge(pending); show = false }) { Text("放弃验证，返回编辑") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (show) {
        LaunchedEffect(pending) {
            if (pending.prompt == CaptchaPrompt.Graphic) {
                try { when (val result = pending.image()) {
                    is ApiResult.Success -> image = result.value
                    is ApiResult.Failure -> { error = result.message; show = false }
                } } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "无法加载验证，请稍后重新操作"; show = false; pending.cancel() }
            }
        }
        Dialog(onDismissRequest = { pending.cancel(); show = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                when (val prompt = pending.prompt) {
                    CaptchaPrompt.Graphic -> {
                        val bytes = image
                        if (bytes == null || progress == CaptchaProgress.LOADING) Column(Modifier.padding(24.dp)) {
                            Text("正在准备验证"); LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = { pending.cancel(); show = false }) { Text("取消") }
                        } else GraphicCaptchaPrompt(bytes, onSubmit = { code -> scope.launch {
                            try { if (!pending.verifyGraphic(code)) error = "验证未通过，原操作没有重发" }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { error = "验证未确认，原操作没有重发"; pending.cancel() }
                            finally { show = false }
                        } }, onCancel = { pending.cancel(); show = false })
                    }
                    is CaptchaPrompt.Netease -> HumanVerification(prompt.spec, onProof = { proof -> scope.launch {
                        try { if (!pending.acceptHumanProof(proof)) { error = "验证已失效"; pending.cancel() } }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "验证已失效"; pending.cancel() }
                        finally { show = false }
                    } }, onCancel = { pending.cancel(); show = false })
                }
            }
        }
    }
}
