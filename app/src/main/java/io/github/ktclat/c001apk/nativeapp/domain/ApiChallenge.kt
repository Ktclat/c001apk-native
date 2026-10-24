// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.domain

/** Server challenge configuration; operationId is a local capability binding, never a proof. */
sealed interface ApiChallenge {
    val operationId: String
    data class Graphic(override val operationId: String = "") : ApiChallenge
    data class Netease(val captchaId: String, val field: String, override val operationId: String = "") : ApiChallenge {
        init {
            require(Regex("^[a-zA-Z0-9]{16,64}$").matches(captchaId))
            require(field.isEmpty() || Regex("^_[a-zA-Z0-9_]{1,63}$").matches(field))
        }
        fun acceptsProof(proof: String): Boolean = proof.startsWith("NEC:${captchaId.take(8)}:") &&
            proof.length in 14..8205 && proof.none { it.isWhitespace() || it.code < 32 || it.code == 127 }
    }
}
