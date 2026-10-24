package io.github.ktclat.c001apk.nativeapp.ui

import kotlin.math.abs

/** Consumed vertical content movement, not pointer attempts or unrelated programmatic scroll. */
internal class FloatingDockScrollState(private val hideDistance: Float, private val showDistance: Float) {
    init { require(hideDistance > 0 && showDistance > 0) }
    var visible: Boolean = true
        private set
    internal var distance: Float = 0f
        private set
    private var userScrolled = false
    private var userFling = false

    fun reset() { visible = true; distance = 0f; userScrolled = false; userFling = false }

    fun consume(deltaY: Float, userInput: Boolean): Boolean {
        if (!deltaY.isFinite() || deltaY == 0f || (!userInput && !userFling)) return visible
        if (userInput) userScrolled = true
        if ((distance > 0f && deltaY < 0f) || (distance < 0f && deltaY > 0f)) distance = 0f
        distance = (distance + deltaY).coerceIn(-hideDistance, showDistance)
        if (distance <= -hideDistance) visible = false
        if (distance >= showDistance) visible = true
        return visible
    }

    fun beginFling(velocityX: Float, velocityY: Float) {
        userFling = userScrolled && velocityY.isFinite() && abs(velocityY) > abs(velocityX)
        userScrolled = false
    }
    fun endFling() { userFling = false; userScrolled = false }
}
