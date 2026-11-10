package com.sillyclient.render

import kotlin.math.abs

internal class ChameleonTouchGesture(private val touchSlop: Float) {
    enum class Result { NONE, TAP, RELOAD }

    var isActive = false
        private set
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var pullReady = false

    fun begin(x: Float, y: Float, canPull: Boolean) {
        isActive = true
        downX = x
        downY = y
        moved = false
        pullReady = canPull
    }

    fun move(x: Float, y: Float, canPull: Boolean) {
        if (!isActive) return
        val dx = abs(x - downX)
        val dy = y - downY
        if (!canPull) pullReady = false
        if (dx > touchSlop || abs(dy) > touchSlop) {
            moved = true
            if (dx > abs(dy) * 0.8f || dy < 0f) pullReady = false
        }
    }

    fun finish(
        x: Float,
        y: Float,
        canPull: Boolean,
        topLimit: Float,
        minPullDistance: Float
    ): Result {
        if (!isActive) return Result.NONE
        move(x, y, canPull)
        val result = when {
            pullReady && canPull && downY <= topLimit && y - downY > minPullDistance -> Result.RELOAD
            !moved -> Result.TAP
            else -> Result.NONE
        }
        cancel()
        return result
    }

    fun cancel() {
        isActive = false
        moved = false
        pullReady = false
    }
}
