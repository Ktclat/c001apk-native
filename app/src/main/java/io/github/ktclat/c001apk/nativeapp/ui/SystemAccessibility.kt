package io.github.ktclat.c001apk.nativeapp.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

data class SystemAccessibility(val reduceMotion: Boolean, val touchExploration: Boolean)
@Composable
fun rememberSystemAccessibility(): SystemAccessibility {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    fun scaleOff() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember { mutableStateOf(scaleOff()) }
    var exploration by remember { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(context, manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploration = it }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = scaleOff() }
        }
        manager.addTouchExplorationStateChangeListener(listener)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener); context.contentResolver.unregisterContentObserver(observer) }
    }
    return SystemAccessibility(reduced, exploration)
}
