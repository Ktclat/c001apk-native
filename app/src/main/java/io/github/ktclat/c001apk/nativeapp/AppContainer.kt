package io.github.ktclat.c001apk.nativeapp

import android.app.Application
import android.os.Build
import io.github.ktclat.c001apk.nativeapp.data.ClientDeviceProfile
import io.github.ktclat.c001apk.nativeapp.data.ClientProtocol
import io.github.ktclat.c001apk.nativeapp.data.HttpCoolapkRepository
import io.github.ktclat.c001apk.nativeapp.domain.CoolapkRepository

class NativeApplication : Application() {
    val container: AppContainer by lazy {
        check(Build.VERSION.SDK_INT < 28 || !Application.getProcessName().endsWith(":public_web")) { "Main stores cannot be opened in the public web process" }
        AppContainer(this)
    }
}
class AppContainer(private val application: Application) {
    val settings = io.github.ktclat.c001apk.nativeapp.settings.SettingsRepository.create(application)
    val local = io.github.ktclat.c001apk.nativeapp.local.LocalRepository.create(application)
    private val preferences = application.getSharedPreferences("native-client", 0)
    private val deviceProfile = ClientDeviceProfile.fromSystemProperties(Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.DISPLAY, Build.VERSION.RELEASE)
    private val localDeviceCode: String = preferences.getString("local-device-code", null)
        ?: (deviceProfile?.newLocalDeviceCode() ?: ClientProtocol().deviceCode).also { preferences.edit().putString("local-device-code", it).apply() }
    private val installedAt: Long = preferences.getLong("installed-at", 0L).let { saved ->
        io.github.ktclat.c001apk.nativeapp.data.InstallationTime.persistedMillis(saved, System.currentTimeMillis()).also { millis ->
            if (millis != saved) preferences.edit().putLong("installed-at", millis).apply()
        }
    }
    private val initialization = kotlinx.coroutines.sync.Mutex()
    private var runtime: io.github.ktclat.c001apk.nativeapp.account.AccountRuntime? = null
    suspend fun accounts(): io.github.ktclat.c001apk.nativeapp.account.AccountRuntime {
        initialization.lock()
        try { return runtime ?: io.github.ktclat.c001apk.nativeapp.account.AccountRuntime.initialize(application, localDeviceCode, Build.VERSION.SDK_INT, deviceProfile, installedAt).also { runtime = it } } finally { initialization.unlock() }
    }
    val nativeUpdates = io.github.ktclat.c001apk.nativeapp.platform.NativeUpdateService(application)
    // Inventory requests are explicitly confirmed in the UI and never share account cookies.
    val appUpdates = io.github.ktclat.c001apk.nativeapp.platform.InstalledAppUpdatesRepository(
        io.github.ktclat.c001apk.nativeapp.data.CoolapkHttpTransport(
            protocol = ClientProtocol(deviceCode = localDeviceCode, sdkInt = Build.VERSION.SDK_INT, deviceProfile = deviceProfile),
        )
    )
    val repository: CoolapkRepository = HttpCoolapkRepository(
        protocol = ClientProtocol(deviceCode = localDeviceCode, sdkInt = Build.VERSION.SDK_INT, deviceProfile = deviceProfile),
        installTimeMillis = installedAt,
    )
}
