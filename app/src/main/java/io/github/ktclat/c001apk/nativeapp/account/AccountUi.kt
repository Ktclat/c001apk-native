package io.github.ktclat.c001apk.nativeapp.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory

/** Host supplies navigation. Never rememberSaveable passwords, captcha proofs or credential objects. */
@Composable
fun AccountManager(store: AccountStore, onAddAccount: () -> Unit, modifier: Modifier = Modifier) {
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    var removeId by remember { mutableStateOf<String?>(null) }
    var recover by remember { mutableStateOf(false) }
    fun action(block: () -> Unit) { scope.launch { error = runCatching { withContext(Dispatchers.IO) { block() } }.isFailure } }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("账号管理", style = MaterialTheme.typography.headlineSmall)
        if (state.recoveryRequired) {
            Text("账号存储无法解密或已损坏。旧数据不会被自动覆盖；重置后需要重新登录。")
            Button(onClick = { recover = true }) { Text("重置账号存储") }
        } else {
            Text(if (state.activeId == null) "当前为游客模式" else "已登录账号可在下方切换")
            state.accounts.forEach { account ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(account.name.ifBlank { account.id })
                        Text(when { account.needsLogin -> "需要重新登录"; state.activeId == account.id -> "当前账号"; else -> "已保存" })
                        Row {
                            TextButton(onClick = { if (account.needsLogin) onAddAccount() else action { store.switchTo(account.id) } }, enabled = state.activeId != account.id) { Text(if (account.needsLogin) "重新登录" else "切换") }
                            TextButton(onClick = { removeId = account.id }) { Text("移除") }
                        }
                    }
                }
            }
            Button(onClick = onAddAccount) { Text("添加账号") }
            OutlinedButton(onClick = { action { store.useGuest() } }, enabled = state.activeId != null) { Text("使用游客模式（保留账号）") }
            if (state.activeId != null) TextButton(onClick = { val generation = state.generation; action { if (store.state.value.generation == generation) store.logout(store.snapshot()) } }) { Text("退出当前账号并清除其凭据") }
        }
        if (error) Text("操作失败，账号状态未更改，请重试。", color = MaterialTheme.colorScheme.error)
    }
    removeId?.let { id -> AlertDialog(onDismissRequest = { removeId = null }, title = { Text("移除这个账号？") }, text = { Text("仅移除本机保存的登录信息，不注销服务器账号。") }, confirmButton = { TextButton(onClick = { removeId = null; action { store.remove(id) } }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removeId = null }) { Text("取消") } }) }
    if (recover) AlertDialog(onDismissRequest = { recover = false }, title = { Text("重置账号存储？") }, text = { Text("将删除本机所有已保存的账号凭据，无法恢复。收藏和历史不会被删除。") }, confirmButton = { TextButton(onClick = { recover = false; action { store.resetAfterRecovery() } }) { Text("重置") } }, dismissButton = { TextButton(onClick = { recover = false }) { Text("取消") } })
}

@Composable
fun PasswordLogin(coordinator: LoginCoordinator, transport: HttpLoginTransport, onComplete: () -> Unit, modifier: Modifier = Modifier) {
    val state by coordinator.state.collectAsState()
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var captchaImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var imageFailed by remember { mutableStateOf(false) }
    var humanProof by remember { mutableStateOf<String?>(null) }
    var showHumanVerification by remember { mutableStateOf(false) }
    LaunchedEffect(coordinator) { coordinator.prepare() }
    DisposableEffect(coordinator) { onDispose { password = ""; coordinator.cancel(); captchaImage?.recycle() } }
    LaunchedEffect(state) {
        if (state is LoginState.HumanVerificationRequired) { humanProof = null; showHumanVerification = true }
        if (state == LoginState.Completed) { password = ""; onComplete() }
        if (state == LoginState.GraphicCaptchaRequired) {
            imageFailed = false
            try {
                val image = transport.graphicCaptcha()
                captchaImage = withContext(Dispatchers.Default) { val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
                    require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000)
                    val options = BitmapFactory.Options()
                    while (bounds.outWidth / options.inSampleSize.coerceAtLeast(1) > 1024 || bounds.outHeight / options.inSampleSize.coerceAtLeast(1) > 1024) options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                    BitmapFactory.decodeByteArray(image, 0, image.size, options) }
                imageFailed = captchaImage == null
            } catch (_: Exception) { imageFailed = true }
        }
    }
    val busy = state == LoginState.Preparing || state == LoginState.Submitting
    Column(modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("登录酷安账号", style = MaterialTheme.typography.headlineSmall)
        Text("密码仅用于本次登录，不保存。登录信息加密保存在本机；验证或风控不会被绕过。")
        OutlinedTextField(login, { login = it }, label = { Text("手机号 / 邮箱 / 用户名") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy, modifier = Modifier.fillMaxWidth())
        captchaImage?.let { Image(it.asImageBitmap(), "图形验证码", Modifier.fillMaxWidth().height(100.dp)) }
        if (state == LoginState.GraphicCaptchaRequired || captchaImage != null) OutlinedTextField(captcha, { captcha = it }, label = { Text("图形验证码") }, singleLine = true)
        if (imageFailed) Text("验证码图片加载失败，请重新准备登录。", color = MaterialTheme.colorScheme.error)
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (state == LoginState.Preparing) "准备登录会话…" else "正在提交，请勿重复操作…") }
        when (state) {
            LoginState.Rejected -> Text("登录未被接受，请检查输入或完成服务要求的验证。", color = MaterialTheme.colorScheme.error)
            LoginState.WebLoginRequired -> Text("服务器要求官方网页登录验证，当前原生版尚未接入此流程。不会套用其他接口的验证码或跳过验证。", color = MaterialTheme.colorScheme.error)
            LoginState.Failed -> Text("登录请求失败或响应不完整，没有自动重试。", color = MaterialTheme.colorScheme.error)
            is LoginState.HumanVerificationRequired -> {
                Text(if (humanProof == null) "服务器要求人机验证，请亲自完成验证。" else "验证已完成，请重新输入密码并点登录，仅重试这一次请求。")
                if (humanProof == null) TextButton(onClick = { showHumanVerification = true }) { Text("完成人机验证") }
            }
            LoginState.Cancelled -> Text("登录已取消或账号已切换，请重新准备。")
            else -> Unit
        }
        Button(onClick = { val secret = password.toCharArray(); password = ""; scope.launch { coordinator.submit(login, secret, captcha, humanProof); humanProof = null } }, enabled = !busy && login.isNotBlank() && password.isNotBlank() && (state !is LoginState.HumanVerificationRequired || humanProof != null) && state != LoginState.Cancelled && state != LoginState.Completed && state != LoginState.WebLoginRequired, modifier = Modifier.fillMaxWidth()) { Text("登录") }
        TextButton(onClick = { password = ""; scope.launch { coordinator.prepare() } }, enabled = !busy) { Text("重新准备登录") }
    }
    val challenge = (state as? LoginState.HumanVerificationRequired)?.challenge
    if (showHumanVerification && challenge != null) Dialog(onDismissRequest = { showHumanVerification = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            HumanVerification(challenge, onProof = { proof -> humanProof = proof; showHumanVerification = false }, onCancel = { showHumanVerification = false })
        }
    }

}
