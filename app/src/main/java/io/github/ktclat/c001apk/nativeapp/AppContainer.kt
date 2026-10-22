package io.github.ktclat.c001apk.nativeapp

import android.app.Application
import android.os.Build
import io.github.ktclat.c001apk.nativeapp.data.ClientProtocol
import io.github.ktclat.c001apk.nativeapp.data.HttpCoolapkRepository
import io.github.ktclat.c001apk.nativeapp.domain.CoolapkRepository

class NativeApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
class AppContainer(private val application: Application) {
    val settings = io.github.ktclat.c001apk.nativeapp.settings.SettingsRepository.create(application)
    val local = io.github.ktclat.c001apk.nativeapp.local.LocalRepository.create(application)
    private val preferences = application.getSharedPreferences("native-client", 0)
    private val localDeviceCode: String = preferences.getString("local-device-code", null)
        ?: ClientProtocol().deviceCode.also { preferences.edit().putString("local-device-code", it).apply() }
    private val installedAt: Long = preferences.getLong("installed-at", 0L).takeIf { it > 0 }
        ?: (System.currentTimeMillis() / 1000).also { preferences.edit().putLong("installed-at", it).apply() }
    private val initialization = kotlinx.coroutines.sync.Mutex()
    private var runtime: io.github.ktclat.c001apk.nativeapp.account.AccountRuntime? = null
    suspend fun accounts(): io.github.ktclat.c001apk.nativeapp.account.AccountRuntime {
        initialization.lock()
        try { return runtime ?: io.github.ktclat.c001apk.nativeapp.account.AccountRuntime.initialize(application, localDeviceCode, Build.VERSION.SDK_INT).also { runtime = it } } finally { initialization.unlock() }
    }
    val repository: CoolapkRepository = HttpCoolapkRepository(
        protocol = ClientProtocol(deviceCode = localDeviceCode, sdkInt = Build.VERSION.SDK_INT),
        installTimeSeconds = installedAt,
    )
}
