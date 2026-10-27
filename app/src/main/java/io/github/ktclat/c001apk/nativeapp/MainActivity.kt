package io.github.ktclat.c001apk.nativeapp

import android.app.Activity
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.ktclat.c001apk.nativeapp.account.AccountRuntime
import io.github.ktclat.c001apk.nativeapp.ui.SessionViewModelStore
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.ui.BrowseViewModel
import io.github.ktclat.c001apk.nativeapp.ui.NativeApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NativeApplication).container
        val sessionOwner = ViewModelProvider(this)[SessionViewModelStore::class.java]
        setContent {
            var initializationAttempt by remember { mutableIntStateOf(0) }
            val runtimeResult by produceState<Result<AccountRuntime>?>(null, initializationAttempt) {
                value = null
                value = try { Result.success(container.accounts()) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (e: Exception) { Result.failure(e) }
            }
            if (runtimeResult == null) { StartupState(); return@setContent }
            val runtime = runtimeResult!!.getOrNull()
            if (runtime == null) { StartupState("账户存储无法初始化，原有数据未被重置。") { initializationAttempt++ }; return@setContent }
            val accountState by runtime.store.state.collectAsStateWithLifecycle()
            sessionOwner.select(accountState.generation)
            val model = remember(accountState.generation) { ViewModelProvider(sessionOwner, viewModelFactory { initializer { BrowseViewModel(runtime.repository) } })[BrowseViewModel::class.java] }

            val settingsState by remember(container) { container.settings.loadStates() }.collectAsStateWithLifecycle(SettingsLoadState.Loading)
            if (settingsState is SettingsLoadState.Failed) {
                StartupState("设置无法读取。数据可能损坏或来自更新版本，原有记录未被覆盖。请保留数据并检查应用版本。")
                return@setContent
            }
            if (settingsState is SettingsLoadState.Loading) { StartupState(); return@setContent }
            val settings = (settingsState as SettingsLoadState.Ready).value
            val dark = when (settings.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
            SideEffect { applyNativeSystemBarAppearance(window, dark) }
            val colors = if (settings.dynamicColor && Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else fixedColorScheme(settings.themeColor, dark)
            val density = LocalDensity.current
            val type = remember(settings.font) { appTypography(settings.font) }
            val reading = remember(settings.font, settings.readingPreset) { readingStyles(settings.readingPreset, settings.font) }
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * settings.fontScale), LocalReadingStyles provides reading) {
                MaterialTheme(colorScheme = colors, typography = type) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides sessionOwner) {
                        key(accountState.generation) { NativeApp(model, container, settings, runtime, accountState) }
                    }
                }
            }
        }
    }
}

@Composable private fun StartupState(message: String? = null, retry: (() -> Unit)? = null) {
    val dark = isSystemInDarkTheme()
    val window = (LocalView.current.context as? Activity)?.window
    SideEffect { window?.let { applyNativeSystemBarAppearance(it, dark) } }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
                if (message == null) CircularProgressIndicator()
                else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(message)
                    if (retry != null) Button(onClick = retry) { Text("重试") }
                }
            }
        }
    }
}

/** Appearance only: keep platform navigation-bar contrast and system gesture protections intact. */
internal fun applyNativeSystemBarAppearance(window: Window, dark: Boolean) {
    // Match activity:1.9.3 EdgeToEdge.kt defaults when our app theme differs from the system.
    // API29+ owns its contrast scrim; leave that navigation color and policy untouched.
    if (Build.VERSION.SDK_INT < 29) {
        window.navigationBarColor = if (dark) 0x801B1B1B.toInt() else 0xE6FFFFFF.toInt()
    }
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}
