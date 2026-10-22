package io.github.ktclat.c001apk.nativeapp

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
import androidx.compose.ui.unit.sp
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
            val runtimeResult by produceState<Result<AccountRuntime>?>(null) {
                value = try { Result.success(container.accounts()) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (e: Exception) { Result.failure(e) }
            }
            if (runtimeResult == null) { MaterialTheme { CircularProgressIndicator() }; return@setContent }
            val runtime = runtimeResult!!.getOrNull()
            if (runtime == null) { MaterialTheme { Text("账户存储无法初始化，原有数据未被重置。") }; return@setContent }
            val accountState by runtime.store.state.collectAsStateWithLifecycle()
            sessionOwner.select(accountState.generation)
            val model = remember(accountState.generation) { ViewModelProvider(sessionOwner, viewModelFactory { initializer { BrowseViewModel(runtime.repository) } })[BrowseViewModel::class.java] }

            val settingsState by remember(container) { container.settings.loadStates() }.collectAsStateWithLifecycle(SettingsLoadState.Loading)
            if (settingsState is SettingsLoadState.Failed) {
                MaterialTheme { androidx.compose.foundation.layout.Column { Text("设置无法读取") ; Text("设置数据可能损坏或来自更新版本，原有记录未被覆盖。请保留数据并检查应用版本。") } }
                return@setContent
            }
            if (settingsState is SettingsLoadState.Loading) { MaterialTheme { CircularProgressIndicator() }; return@setContent }
            val settings = (settingsState as SettingsLoadState.Ready).value
            val dark = when (settings.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
            val colors = if (settings.dynamicColor && Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            val density = LocalDensity.current
            val base = Typography()
            val type = if (settings.readingPreset == ReadingPreset.COMFORTABLE) base.copy(bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 27.sp)) else base
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * settings.fontScale)) {
                MaterialTheme(colorScheme = colors, typography = type) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides sessionOwner) {
                        key(accountState.generation) { NativeApp(model, container, settings, runtime, accountState) }
                    }
                }
            }
        }
    }
}
