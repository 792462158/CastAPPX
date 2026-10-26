package com.android.castappx.selfDesignView

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * 页面内的堆叠提示，适用于 Android View 项目（minSdk >= 19）。
 * 每个 Activity 创建一个实例；所有公开方法在主线程调用。
 * Activity 进入后台时清空，销毁时自动释放。
 */
@MainThread
class StackedToast(
    activity: ComponentActivity,
    private val maxVisible: Int = 12,
    private val defaultDurationMillis: Long = 2_500L,
) : DefaultLifecycleObserver {

    private val lifecycle = activity.lifecycle
    private val handler = Handler(Looper.getMainLooper())
    private val pending = LinkedHashMap<View, Runnable>()
    private var released = false

    private val parent = activity.findViewById<ViewGroup>(android.R.id.content)
    private val overlay = FrameLayout(activity).apply {
        // 空白区域不拦截触摸，页面原有按钮仍可点击。
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        val density = resources.displayMetrics.density
        val horizontal = (24 * density + 0.5f).toInt()
        val bottom = (32 * density + 0.5f).toInt()
        setPadding(horizontal, 0, horizontal, bottom)
    }
    private val stack = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    init {
        checkMainThread()
        require(maxVisible > 0) { "maxVisible 必须大于 0" }
        require(defaultDurationMillis > 0) { "显示时长必须大于 0" }
        check(lifecycle.currentState != Lifecycle.State.DESTROYED) {
            "Activity 已销毁"
        }

        overlay.addView(
            stack,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )
        parent.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // 使用实际窗口坐标计算重叠，兼容内容区已避让系统栏的页面。
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { view, insets ->
            view.post {
                if (!released) updateInsets(insets)
            }
            insets
        }
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            ViewCompat.getRootWindowInsets(overlay)?.let(::updateInsets)
        }
        ViewCompat.requestApplyInsets(overlay)
        lifecycle.addObserver(this)
    }

    fun show(message: CharSequence, durationMillis: Long = defaultDurationMillis) {
        checkMainThread()
        require(durationMillis > 0) { "显示时长必须大于 0" }
        if (released || message.isBlank()) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        // 复用已布局的容器时，在动画开始前同步安全区域。
        ViewCompat.getRootWindowInsets(overlay)?.let(::updateInsets)
        overlay.bringToFront()

        while (stack.childCount >= maxVisible) {
            remove(stack.getChildAt(0))
        }

        val item = TextView(overlay.context).apply {
            alpha = 0f
            text = message
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 45, 45, 45))
                cornerRadius = dp(12).toFloat()
            }
            ViewCompat.setAccessibilityLiveRegion(
                this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE,
            )
        }

        stack.addView(
            item,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            },
        )

        item.animate().alpha(1f).setDuration(180L).start()

        val expire = Runnable {
            if (!released) remove(item, animated = true)
        }
        pending[item] = expire
        handler.postDelayed(expire, durationMillis)
    }

    fun clear() {
        checkMainThread()
        handler.removeCallbacksAndMessages(null)
        pending.clear()
        for (index in 0 until stack.childCount) {
            stack.getChildAt(index).animate().withEndAction(null).cancel()
        }
        stack.removeAllViews()
    }

    override fun onStop(owner: LifecycleOwner) = clear()

    override fun onDestroy(owner: LifecycleOwner) {
        checkMainThread()
        released = true
        clear()
        parent.removeView(overlay)
        lifecycle.removeObserver(this)
    }

    private fun remove(item: View, animated: Boolean = false) {
        pending.remove(item)?.let(handler::removeCallbacks)
        item.animate().withEndAction(null).cancel()
        if (item.parent !== stack) return

        if (animated) {
            // 淡出期间保留布局位置，避免移除 View 时父容器重排影响退出动画。
            item.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction {
                    if (item.parent === stack) stack.removeView(item)
                }
                .start()
        } else {
            stack.removeView(item)
        }
    }

    private fun updateInsets(insets: WindowInsetsCompat) {
        if (released || overlay.height == 0) return
        val safe = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
        )
        val root = overlay.rootView
        val location = IntArray(2)
        val rootLocation = IntArray(2)
        overlay.getLocationInWindow(location)
        root.getLocationInWindow(rootLocation)
        val leftOverlap = (rootLocation[0] + safe.left - location[0]).coerceAtLeast(0)
        val rightOverlap = (
                location[0] + overlay.width - (rootLocation[0] + root.width - safe.right)
                ).coerceAtLeast(0)
        val bottomOverlap = (
                location[1] + overlay.height - (rootLocation[1] + root.height - safe.bottom)
                ).coerceAtLeast(0)
        val left = dp(24) + leftOverlap
        val right = dp(24) + rightOverlap
        val bottom = dp(32) + bottomOverlap
        if (overlay.paddingLeft != left || overlay.paddingRight != right ||
            overlay.paddingBottom != bottom
        ) {
            overlay.setPadding(left, 0, right, bottom)
        }
    }

    private fun dp(value: Int): Int =
        (value * overlay.resources.displayMetrics.density + 0.5f).toInt()

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "StackedToast 必须在主线程调用"
        }
    }
}
