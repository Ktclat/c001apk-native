package io.github.ktclat.c001apk.nativeapp.ui

import kotlinx.coroutines.Job
import java.util.IdentityHashMap

/** TEMPORARY CI17 diagnostic. Not a production fix or eligible for accepted publication. */
internal object PostParseTrace {
    private const val MAX_EVENTS = 2048
    private const val MAX_STATES = 512
    private val lock = Any()
    private val states = IdentityHashMap<Any, Int>()
    private val events = ArrayList<String>(MAX_EVENTS)
    private var sequence = 0L
    private var startedNanos = 0L
    private var dropped = 0L
    @Volatile var enabled = false
        private set

    fun start() = synchronized(lock) {
        enabled = false
        states.clear()
        events.clear()
        sequence = 0
        dropped = 0
        startedNanos = System.nanoTime()
        enabled = true
    }

    fun stop() { enabled = false }

    fun id(state: Any): Int {
        if (!enabled) return 0
        return synchronized(lock) { stateId(state) }
    }

    private fun stateId(state: Any): Int {
        states[state]?.let { return it }
        if (states.size >= MAX_STATES) return -1
        return (states.size + 1).also { states[state] = it }
    }

    fun record(state: Any, event: String, job: Job? = null, ready: Boolean? = null) {
        if (!enabled) return
        synchronized(lock) {
            if (!enabled) return
            if (events.size >= MAX_EVENTS) { dropped++; return }
            val id = stateId(state)
            val thread = Thread.currentThread()
            // Never record source HTML, parsed text, navigation IDs, account data or exception messages.
            events.add("${++sequence} +${System.nanoTime() - startedNanos}ns state=$id " +
                "event=$event ready=${ready ?: "-"} " +
                "job=${job?.let(System::identityHashCode) ?: 0} active=${job?.isActive ?: "-"} " +
                "thread=${thread.id}:${thread.name}")
        }
    }

    fun snapshot(): String = synchronized(lock) {
        "diagnostic-only states=${states.size}/$MAX_STATES events=${events.size}/$MAX_EVENTS dropped=$dropped\n" +
            events.joinToString("\n", postfix = "\n")
    }
}
