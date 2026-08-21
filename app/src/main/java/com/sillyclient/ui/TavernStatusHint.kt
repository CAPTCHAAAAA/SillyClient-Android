package com.sillyclient.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 酒馆状态栏内的一次性返回手势提示。
 *
 * 使用最初验证过的直接 TextView 渲染方式；文字固定显示，不做容器和滚动包装。
 */
class TavernStatusHint(private val activity: Activity) {

    private val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var host: FrameLayout? = null
    private var barHeight = 0
    private var areaLeft = 0
    private var areaRight = 0
    private var cameraHeightPx = 0
    private var hintView: ShimmerTextView? = null
    private var currentInstanceId: String? = null

    @Suppress("DEPRECATION")
    private val versionKey: String by lazy {
        val info = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }.getOrNull()
        val versionCode = if (info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            info?.versionCode?.toString() ?: "0"
        }
        "${info?.versionName ?: "0"}:$versionCode"
    }

    fun attach(
        host: FrameLayout,
        barHeight: Int,
        areaLeft: Int,
        areaRight: Int,
        cameraHeightPx: Int = 0
    ) {
        this.host = host
        this.barHeight = barHeight.coerceAtLeast(0)
        this.areaLeft = areaLeft.coerceAtLeast(0)
        this.areaRight = areaRight.coerceAtLeast(this.areaLeft + dp(24))
        this.cameraHeightPx = cameraHeightPx.coerceAtLeast(0)
    }

    fun show(instanceId: String?) {
        val normalizedId = instanceId?.trim().orEmpty()
        val used = preferences.getBoolean(usedKey(normalizedId), false)
        Log.i(
            TAG,
            "show requested id=$normalizedId used=$used version=$versionKey host=${host != null}"
        )
        if (normalizedId.isEmpty() || used) return
        val parent = host ?: return
        currentInstanceId = normalizedId
        if (hintView != null) return

        val view = ShimmerTextView(activity).apply {
            text = HINT_TEXT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = false
            isFocusable = false
            alpha = 0f
        }
        val regionTop = barHeight / 4
        val regionHeight = (barHeight - regionTop).coerceAtLeast(1)
        view.layoutParams = FrameLayout.LayoutParams(
            areaRight - areaLeft,
            regionHeight
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            leftMargin = areaLeft
            this.topMargin = regionTop
        }

        parent.addView(view)
        hintView = view
        Log.i(TAG, "Shown for $normalizedId left=$areaLeft right=$areaRight top=$regionTop")
        view.updateTone(isDarkScrim = true)
        view.animate().alpha(0.72f).setDuration(220).start()
        view.startShimmer()
    }

    /** 只有真实滑动返回才调用：写入已学会状态并立即隐藏。 */
    fun markUsed() {
        val instanceId = currentInstanceId ?: return
        preferences.edit().putBoolean(usedKey(instanceId), true).apply()
        dismiss()
    }

    fun dismiss() {
        val view = hintView ?: return
        hintView = null
        currentInstanceId = null
        view.stopShimmer()
        view.animate().cancel()
        (view.parent as? ViewGroup)?.removeView(view)
    }

    /** 变色龙取色变化时同步调整文字明暗。 */
    fun onColorChanged(color: Int) {
        val scrimTop = TopColor.darken(color, 0.45f)
        hintView?.updateTone(TopColor.isDark(scrimTop))
    }

    private fun usedKey(instanceId: String) = "$USED_KEY_PREFIX$versionKey:$instanceId"

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val PREFERENCES_NAME = "tavern_gesture_hints"
        const val USED_KEY_PREFIX = "used:"
        const val HINT_TEXT = "左右滑动状态栏返回"
        const val TAG = "TavernStatusHint"
    }
}

/** TextView 子类：最初验证过的渐变流光渲染。 */
private class ShimmerTextView(context: Context) : TextView(context) {

    init {
        setTextColor(0xFFE0E0E0.toInt())
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private var darkTone = true
    private var shimmerShader: LinearGradient? = null
    private var animator: ValueAnimator? = null
    private var shimmerOffset = 0f

    private val darkColors = intArrayOf(
        0x40E0E0E0.toInt(),
        0xB0E0E0E0.toInt(),
        0xE8E0E0E0.toInt(),
        0xB0E0E0E0.toInt(),
        0x40E0E0E0.toInt()
    )
    private val lightColors = intArrayOf(
        0x40282828.toInt(),
        0xB0282828.toInt(),
        0xE8282828.toInt(),
        0xB0282828.toInt(),
        0x40282828.toInt()
    )

    fun updateTone(isDarkScrim: Boolean) {
        if (darkTone == isDarkScrim) return
        darkTone = isDarkScrim
        setTextColor(if (darkTone) 0xFFE0E0E0.toInt() else 0xFF282828.toInt())
        rebuildShader(width)
        invalidate()
    }

    fun startShimmer() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val fraction = va.animatedValue as Float
                setShimmerOffset(width * 3f * fraction)
            }
            start()
        }
    }

    fun stopShimmer() {
        animator?.cancel()
        animator = null
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuildShader(width)
    }

    override fun onDraw(canvas: Canvas) {
        val shader = shimmerShader
        val paint = paint
        if (shader != null && width > 0) {
            val matrix = Matrix()
            matrix.setTranslate(shimmerOffset - width.toFloat(), 0f)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
        } else {
            paint.shader = null
        }
        super.onDraw(canvas)
        paint.shader = null
    }

    private fun setShimmerOffset(offset: Float) {
        shimmerOffset = offset
        postInvalidateOnAnimation()
    }

    private fun rebuildShader(viewWidth: Int) {
        shimmerShader = if (viewWidth > 0) {
            LinearGradient(
                0f,
                0f,
                viewWidth * 3f,
                0f,
                if (darkTone) darkColors else lightColors,
                null,
                Shader.TileMode.REPEAT
            )
        } else {
            null
        }
    }
}
