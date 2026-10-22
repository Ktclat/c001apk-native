package io.github.ktclat.c001apk.nativeapp.account

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class LoginCoordinatorTest {
    private fun store() = AccountStore(object : CredentialPersistence {
        override fun read(): ByteArray? = null
        override fun write(bytes: ByteArray) = Unit
        override fun reset() = Unit
    })
    @Test fun passwordsAreClearedAndHumanChallengeOnlyRetriesOnce() = runTest {
        val challenge = HumanChallengeSpec("1234567890abcdef", "_v2_post_token")
        var calls = 0
        val transport = object : LoginTransport {
            override suspend fun prepare() = LoginPreparation("synthetic-hash", "123")
            override suspend fun submit(preparation: LoginPreparation, login: String, password: CharArray, captcha: String, proof: String?): LoginReply {
                calls++; return LoginReply.HumanChallenge(challenge)
            }
            override fun clear() = Unit
        }
        val coordinator = LoginCoordinator(store(), transport, "device")
        coordinator.prepare()
        val password = charArrayOf('x'); coordinator.submit("test", password)
        assertEquals('\u0000', password[0]); assertEquals(1, calls)
        coordinator.submit("test", charArrayOf('x'), humanProof = "invalid")
        assertEquals(1, calls)
        coordinator.submit("test", charArrayOf('x'), humanProof = "NEC:12345678:synthetic")
        assertEquals(2, calls); assertEquals(LoginState.Rejected, coordinator.state.value)
        coordinator.submit("test", charArrayOf('x'), humanProof = "NEC:12345678:synthetic")
        assertEquals(2, calls)
    }
    @Test fun accountSwitchPreventsSubmittingStaleLogin() = runTest {
        val store = store(); var calls = 0
        val coordinator = LoginCoordinator(store, object : LoginTransport {
            override suspend fun prepare() = LoginPreparation("synthetic", "123")
            override suspend fun submit(preparation: LoginPreparation, login: String, password: CharArray, captcha: String, proof: String?): LoginReply { calls++; return LoginReply.Rejected() }
            override fun clear() = Unit
        }, "device")
        coordinator.prepare(); store.useGuest()
        val password = charArrayOf('x'); coordinator.submit("test", password)
        assertEquals(0, calls); assertEquals('\u0000', password[0])
    }
    @Test fun challengeRejectsControlCharactersAndInvalidFields() {
        val spec = HumanChallengeSpec("1234567890abcdef", "")
        assertFalse(spec.accepts("NEC:12345678:bad\nvalue"))
        assertFalse(spec.accepts("NEC:abcdefgh:synthetic"))
        try { HumanChallengeSpec("1234567890abcdef", "password"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun concurrentSubmitNeverPostsTwice() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val coordinator = LoginCoordinator(store(), object : LoginTransport {
            override suspend fun prepare() = LoginPreparation("synthetic", "123")
            override suspend fun submit(preparation: LoginPreparation, login: String, password: CharArray, captcha: String, proof: String?): LoginReply {
                calls++; entered.complete(Unit); release.await(); return LoginReply.Rejected()
            }
            override fun clear() = Unit
        }, "device")
        coordinator.prepare()
        val first = launch { coordinator.submit("test", charArrayOf('x')) }
        entered.await()
        coordinator.submit("test", charArrayOf('x'))
        coordinator.submit("test", charArrayOf('x'))
        assertEquals(1, calls)
        release.complete(Unit); first.join()
    }
    @Test fun lateFailureDoesNotOverwriteNewPreparation() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val coordinator = LoginCoordinator(store(), object : LoginTransport {
            override suspend fun prepare(): LoginPreparation {
                calls++
                if (calls == 1) { entered.complete(Unit); release.await(); error("late failure") }
                return LoginPreparation("synthetic-new", "123")
            }
            override suspend fun submit(preparation: LoginPreparation, login: String, password: CharArray, captcha: String, proof: String?) = LoginReply.Rejected()
            override fun clear() = Unit
        }, "device")
        val first = launch { coordinator.prepare() }; entered.await()
        coordinator.prepare(); assertEquals(LoginState.Ready, coordinator.state.value)
        release.complete(Unit); first.join(); assertEquals(LoginState.Ready, coordinator.state.value)
    }
}
