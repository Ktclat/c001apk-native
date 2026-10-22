package io.github.ktclat.c001apk.nativeapp.account

import android.content.Context
import io.github.ktclat.c001apk.nativeapp.data.*
import io.github.ktclat.c001apk.nativeapp.social.SocialRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Initialize before displaying account-aware screens; recreate screen caches on store generation changes. */
class AccountRuntime private constructor(val store: AccountStore, val loginTransport: HttpLoginTransport,
    val loginCoordinator: LoginCoordinator, val repository: HttpCoolapkRepository, val socialRepository: SocialRepository) {
    companion object {
        suspend fun initialize(context: Context, stableDeviceCode: String, sdkInt: Int, deviceProfile: ClientDeviceProfile? = null,
            installTimeMillis: Long = System.currentTimeMillis()): AccountRuntime = withContext(Dispatchers.IO) {
            require(stableDeviceCode.isNotBlank())
            val store = AccountStore(EncryptedCredentialPersistence(context.applicationContext))
            val protocol = ClientProtocol(deviceCode = stableDeviceCode, sdkInt = sdkInt, deviceProfile = deviceProfile)
            val loginTransport = HttpLoginTransport()
            val loginCoordinator = LoginCoordinator(store, loginTransport, stableDeviceCode)
            val repository = HttpCoolapkRepository(protocol = protocol, installTimeMillis = installTimeMillis, requestSession = { store.captureRequestSession() })
            val social = SocialRepository(store) { snapshot ->
                CoolapkHttpTransport(protocol = protocol, requestSession = { store.requestSessionFor(snapshot) })
            }
            AccountRuntime(store, loginTransport, loginCoordinator, repository, social)
        }
    }
}
