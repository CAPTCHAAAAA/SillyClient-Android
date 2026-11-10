package com.sillyclient.render

import com.sillyclient.render.ChameleonTouchGesture.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChameleonTouchGestureTest {
    private fun finish(
        gesture: ChameleonTouchGesture,
        x: Float = 10f,
        y: Float = 20f,
        canPull: Boolean = false
    ): Result = gesture.finish(x, y, canPull, topLimit = 100f, minPullDistance = 100f)

    @Test
    fun tapsAndSwipesAreClassifiedIndependentlyOfPullToRefresh() {
        for (canPull in listOf(false, true)) {
            val gesture = ChameleonTouchGesture(8f)
            gesture.begin(10f, 20f, canPull)
            assertTrue(gesture.isActive)
            assertEquals(Result.TAP, finish(gesture, canPull = canPull))
            assertFalse(gesture.isActive)

            gesture.begin(10f, 20f, canPull)
            gesture.move(10f, 70f, canPull)
            assertEquals(Result.NONE, finish(gesture, y = 70f, canPull = canPull))
        }
    }

    @Test
    fun movementOnTheFinalEventCannotBecomeATap() {
        val gesture = ChameleonTouchGesture(8f)
        gesture.begin(10f, 20f, false)

        assertEquals(Result.NONE, finish(gesture, x = 40f))
    }

    @Test
    fun onlyAnEnabledDownwardPullFromTheTopReloads() {
        val gesture = ChameleonTouchGesture(8f)
        gesture.begin(10f, 20f, true)
        gesture.move(10f, 140f, true)
        assertEquals(Result.RELOAD, finish(gesture, y = 140f, canPull = true))

        gesture.begin(10f, 20f, false)
        gesture.move(10f, 140f, false)
        assertEquals(Result.NONE, finish(gesture, y = 140f))

        gesture.begin(10f, 120f, true)
        gesture.move(10f, 240f, true)
        assertEquals(Result.NONE, finish(gesture, y = 240f, canPull = true))
    }

    @Test
    fun horizontalOrUpwardMovementDisqualifiesPullEvenAfterDirectionChanges() {
        val gesture = ChameleonTouchGesture(8f)
        for ((x, y) in listOf(40f to 25f, 10f to 0f)) {
            gesture.begin(10f, 20f, true)
            gesture.move(x, y, true)
            gesture.move(10f, 140f, true)
            assertEquals(Result.NONE, finish(gesture, y = 140f, canPull = true))
        }
    }

    @Test
    fun pullCannotBeEnabledHalfwayThroughTheGesture() {
        val gesture = ChameleonTouchGesture(8f)
        gesture.begin(10f, 20f, false)
        gesture.move(10f, 140f, true)

        assertEquals(Result.NONE, finish(gesture, y = 140f, canPull = true))
    }

    @Test
    fun disablingPullOrLeavingTheTopCancelsReloadForTheWholeGesture() {
        val gesture = ChameleonTouchGesture(8f)
        gesture.begin(10f, 20f, true)
        gesture.move(10f, 60f, false)
        gesture.move(10f, 140f, true)

        assertEquals(Result.NONE, finish(gesture, y = 140f, canPull = true))
    }

    @Test
    fun cancelAndMissingDownCannotProduceAnAction() {
        val gesture = ChameleonTouchGesture(8f)
        assertEquals(Result.NONE, finish(gesture))
        gesture.begin(10f, 20f, true)
        gesture.cancel()

        assertFalse(gesture.isActive)
        assertEquals(Result.NONE, finish(gesture, y = 140f, canPull = true))
    }

    @Test
    fun aNewDownReplacesAnInterruptedGesture() {
        val gesture = ChameleonTouchGesture(8f)
        gesture.begin(10f, 20f, true)
        gesture.move(10f, 140f, true)
        gesture.begin(30f, 40f, false)

        assertEquals(Result.TAP, finish(gesture, x = 30f, y = 40f))
    }
}
