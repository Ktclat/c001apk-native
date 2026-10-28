// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import io.github.ktclat.c001apk.nativeapp.account.AccountState
import io.github.ktclat.c001apk.nativeapp.data.RequestSession
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Test-only observations: no additional reads, requests, dispatchers, waits or retries.
 * Stack collection and successful-run output can affect timing; green is not a reproduction/fix.
 */
internal class ReachableReadinessDiagnostics {
    private val started = System.nanoTime()
    private val events = CopyOnWriteArrayList<String>()
    private val sessionIds = AtomicInteger()
    private val initialResources = resources()

    fun event(message: String) {
        events += "${(System.nanoTime() - started) / 1_000_000} ms [${Thread.currentThread().name}] $message"
    }

    /** Observe only callbacks that production already invokes; never probe account currentness. */
    fun session(raw: RequestSession): RequestSession {
        val id = sessionIds.incrementAndGet()
        event("session#$id captured guest=${raw.isGuest} generation=${raw.diagnosticGeneration}")
        fun observed(label: String, check: () -> Boolean): Boolean {
            val caller = Throwable().stackTrace.filter {
                it.className.startsWith("io.github.ktclat.c001apk.nativeapp.") &&
                    !it.className.startsWith(ReachableReadinessDiagnostics::class.java.name)
            }.take(4).joinToString(" <- ")
            event("session#$id $label begin; caller=$caller")
            return try {
                check().also { event("session#$id $label returned $it") }
            } catch (failure: Throwable) {
                try { event("session#$id $label threw ${failure.javaClass.name}") }
                catch (diagnosticFailure: Throwable) {
                    if (diagnosticFailure !== failure) failure.addSuppressed(diagnosticFailure)
                }
                throw failure
            }
        }
        return raw.copy(
            isCurrent = { observed("isCurrent", raw.isCurrent) },
            isSessionCurrent = raw.isSessionCurrent?.let { check -> { observed("isSessionCurrent", check) } },
            isReadSessionCurrent = raw.isReadSessionCurrent?.let { check -> { observed("isReadSessionCurrent", check) } },
            acceptCookies = { url, headers ->
                // This ordinary-response callback is after the envelope decoder, not proof
                // that the personal-entry decoder or Compose state publication completed.
                event("session#$id acceptCookies begin path=${url.encodedPath} count=${headers.size}")
                raw.acceptCookies(url, headers)
                event("session#$id acceptCookies returned")
            },
        )
    }

    /** Return the identical source; do not read, buffer, replace, drain or delay fixture bytes. */
    fun body(path: String, delegate: ResponseBody): ResponseBody = object : ResponseBody() {
        private val accesses = AtomicInteger()
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source() = delegate.source().also {
            // ResponseBody.close also calls source(); access alone is not a decode-success signal.
            event("response $path source access #${accesses.incrementAndGet()}")
        }
    }

    /** Called before screenshot/Compose idle work, so failure evidence does not depend on it. */
    fun save(name: String, outcome: String, state: AccountState, client: OkHttpClient, includeThreads: Boolean) {
        val threads = if (includeThreads) Thread.getAllStackTraces() else emptyMap()
        val report = buildString {
            appendLine("outcome=$outcome; elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            appendLine("Non-atomic post-wait snapshot (post-timeout on timeout); work may progress during collection.")
            appendLine("Phase stacks/output can affect timing; a successful run does not reproduce or resolve CI62.")
            appendLine("account activeId=${state.activeId}; generation=${state.generation}; recoveryRequired=${state.recoveryRequired}; authenticatedId=${personalListAccountId(state)}")
            appendLine("dispatcher running=${client.dispatcher.runningCallsCount()}; queued=${client.dispatcher.queuedCallsCount()}; executorShutdown=${client.dispatcher.executorService.isShutdown}")
            appendLine("initial $initialResources")
            appendLine("current ${resources()}")
            appendLine("Phases (source access is not delivery/decode completion; no credentials or bodies):")
            events.toList().forEach { appendLine(it) }
            threads.entries.sortedWith(compareBy({ it.key.name }, { it.key.id })).forEach { (thread, stack) ->
                appendLine("THREAD ${thread.id} ${thread.name} state=${thread.state} daemon=${thread.isDaemon}")
                stack.forEach { appendLine("  at $it") }
            }
        }
        File("build/verification/reachable-cards/$name-readiness-phases.txt")
            .apply { parentFile!!.mkdirs() }.writeText(report)
    }

    private fun resources(): String {
        val memory = ManagementFactory.getMemoryMXBean()
        return "jvmUptimeMs=${ManagementFactory.getRuntimeMXBean().uptime}; " +
            "heap=${memory.heapMemoryUsage}; nonHeap=${memory.nonHeapMemoryUsage}; " +
            "gc=" + ManagementFactory.getGarbageCollectorMXBeans().joinToString { bean ->
                "${bean.name}(count=${bean.collectionCount},timeMs=${bean.collectionTime})"
            }
    }
}
