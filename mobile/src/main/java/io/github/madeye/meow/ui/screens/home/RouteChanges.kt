package io.github.madeye.meow.ui.screens.home

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A node or route-mode switch has reached the running engine, so Home's
 * exit-IP card re-checks. App-wide rather than one screen's: route modes are
 * switched on Home but nodes on the Proxy Groups tab, while the card's
 * ViewModel stays alive on the back stack under either.
 */
class RouteChanges {
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val events: SharedFlow<Unit> = changes.asSharedFlow()

    fun notifyChanged() {
        changes.tryEmit(Unit)
    }
}
