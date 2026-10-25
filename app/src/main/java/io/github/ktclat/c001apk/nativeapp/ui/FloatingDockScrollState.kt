package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/** Consumed vertical content movement, not pointer attempts or unrelated programmatic scroll. */
internal class FloatingDockScrollState(
    private val hideDistance: Float,
    private val showDistance: Float,
    val travelExtent: Float = 74f,
    private val reduceMotion: Boolean = false,
) {
    init { require(hideDistance.isFinite() && hideDistance > 0 && showDistance.isFinite() && showDistance > 0 && travelExtent.isFinite() && travelExtent > 0) }
    var hiddenFraction: Float by mutableFloatStateOf(0f)
        private set
    val visible: Boolean get() = hiddenFraction < 1f
    val fullyShown: Boolean get() = hiddenFraction == 0f
    internal var distance: Float = 0f
        private set
    private var userScrolled = false
    private var userFling = false

    fun reset() { hiddenFraction = 0f; distance = 0f; userScrolled = false; userFling = false }

    fun consume(deltaY: Float, userInput: Boolean): Boolean {
        if (!deltaY.isFinite() || deltaY == 0f || (!userInput && !userFling)) return visible
        if (userInput) userScrolled = true
        if ((distance > 0f && deltaY < 0f) || (distance < 0f && deltaY > 0f)) distance = 0f
        distance = (distance + deltaY).coerceIn(-hideDistance, showDistance)
        if (reduceMotion) {
            if (distance <= -hideDistance) hiddenFraction = 1f
            if (distance >= showDistance) hiddenFraction = 0f
        } else hiddenFraction = (hiddenFraction - deltaY / travelExtent).coerceIn(0f, 1f)
        return visible
    }

    fun beginFling(velocityX: Float, velocityY: Float) {
        userFling = userScrolled && velocityX.isFinite() && velocityY.isFinite() && abs(velocityY) > abs(velocityX)
    }

    /** Flutter's directional intent thresholds apply at rest, never as a mid-drag visibility jump. */
    fun endFling(): Float {
        val target = if (reduceMotion || !userScrolled) hiddenFraction else when {
            distance <= -hideDistance -> 1f
            distance >= showDistance -> 0f
            hiddenFraction >= .5f -> 1f
            else -> 0f
        }
        userFling = false; userScrolled = false; distance = 0f
        return target
    }

    /** Called only by the current owner's cancellable settling animation. */
    fun settleTo(value: Float) {
        if (value.isFinite()) hiddenFraction = value.coerceIn(0f, 1f)
    }
}
