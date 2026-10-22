package io.github.ktclat.c001apk.nativeapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** One active session only. Switching invalidates all presentation jobs and cached pages before rendering. */
class SessionViewModelStore : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private var generation: Long? = null
    fun select(next: Long) {
        if (generation != next) { viewModelStore.clear(); generation = next }
    }
    override fun onCleared() { viewModelStore.clear() }
}
