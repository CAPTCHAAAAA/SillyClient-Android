package com.sillyclient.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView

/** Android 酒馆页顶部的一次性返回手势提示。 */
class TavernGestureHint(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var host: FrameLayout? = null
    private var topInset = 0
    private var hintView: TextView? = null
    private val dismissRunnable = Runnable { fadeOut() }

    fun attach(host: FrameLayout, topInset: Int) {
        this.host = host
        this.topInset = topInset.coerceAtLeast(0)
    }

    fun show(instanceId: String?) {
        val normalizedId = instanceId?.trim().orEmpty()
        if (normalizedId.isEmpty() || preferences.getBoolean(preferenceKey(normalizedId), false)) return
        val parent = host ?: return

        dismiss()
        val view = TextView(activity).apply {
            text = "在顶部状态栏左右滑动，即可返回 SillyClient"
            setTextColor(0xE8FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = false
            isFocusable = false
            alpha = 0f
            translationY = -dp(8).toFloat()
            setPadding(dp(16), dp(11), dp(16), dp(11))
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xDC1A181D.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x24FFFFFF)
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                leftMargin = dp(16)
                rightMargin = dp(16)
                topMargin = topInset + dp(10)
            }
        }

        parent.addView(view)
        hintView = view
        preferences.edit().putBoolean(preferenceKey(normalizedId), true).apply()
        Log.i(TAG, "Shown for instance $normalizedId")
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .start()
        handler.postDelayed(dismissRunnable, DISPLAY_DURATION_MS)
    }

    fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        val view = hintView ?: return
        hintView = null
        view.animate().cancel()
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun fadeOut() {
        handler.removeCallbacks(dismissRunnable)
        val view = hintView ?: return
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .translationY(-dp(6).toFloat())
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (hintView === view) hintView = null
                    (view.parent as? ViewGroup)?.removeView(view)
                }
            })
            .start()
    }

    private fun preferenceKey(instanceId: String) = "$PREFERENCE_KEY_PREFIX$instanceId"

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val PREFERENCES_NAME = "tavern_gesture_hints"
        const val PREFERENCE_KEY_PREFIX = "shown:"
        const val DISPLAY_DURATION_MS = 4600L
        const val TAG = "TavernGestureHint"
    }
}
