package io.github.ktclat.c001apk.nativeapp.account

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Presentation only. Caller binds these bytes and explicit user input to its captured API session.
 * No login challenge, network call, proof construction or automatic retry belongs in this form. */
@Composable
fun GraphicCaptchaPrompt(imageBytes: ByteArray, onSubmit: (String) -> Unit, onCancel: () -> Unit,
    modifier: Modifier = Modifier) {
    var code by remember(imageBytes) { mutableStateOf("") }
    var submitted by remember(imageBytes) { mutableStateOf(false) }
    var loaded by remember(imageBytes) { mutableStateOf(false) }
    var bitmap by remember(imageBytes) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(imageBytes) {
        try { bitmap = withContext(Dispatchers.Default) { decodeGraphicCaptcha(imageBytes) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { bitmap = null }
        loaded = true
    }
    Column(modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("完成图形验证", style = MaterialTheme.typography.titleLarge)
        Text("请亲自输入图片中的四位字母或数字。提交验证不会自动重发原操作。")
        bitmap?.let { Image(it.asImageBitmap(), "待输入的图形验证码", Modifier.fillMaxWidth().height(100.dp)) }
        if (!loaded) CircularProgressIndicator()
        if (loaded && bitmap == null) Text("验证码图片无效，请取消后重新获取。", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(code, { if (it.length <= 4 && it.all { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' }) code = it },
            label = { Text("四位验证码") }, singleLine = true, enabled = bitmap != null && !submitted,
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (!submitted) { submitted = true; val input = code; code = ""; onSubmit(input) } },
            enabled = bitmap != null && code.length == 4 && !submitted, modifier = Modifier.fillMaxWidth()) { Text("提交验证") }
        TextButton(onClick = { code = ""; onCancel() }) { Text("取消") }
    }
}

internal fun decodeGraphicCaptcha(bytes: ByteArray): Bitmap? {
    require(bytes.size in 1..2 * 1024 * 1024)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000)
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (bounds.outWidth / options.inSampleSize > 1024 || bounds.outHeight / options.inSampleSize > 1024) options.inSampleSize *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
