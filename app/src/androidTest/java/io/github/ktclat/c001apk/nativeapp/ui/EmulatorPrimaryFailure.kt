// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

/** Test execution is serial. Preserve the original @Test failure before any @After/UI teardown. */
internal object EmulatorPrimaryFailure { var current: Throwable? = null }
internal fun recordPrimaryTestFailure(name: String, block: () -> Unit) {
    EmulatorPrimaryFailure.current = null
    try { block() } catch (failure: Throwable) {
        EmulatorPrimaryFailure.current = failure
        runCatching { EmulatorEvidence.text("$name-primary-test-failure.txt", failure.stackTraceToString()) }
        throw failure
    }
}
