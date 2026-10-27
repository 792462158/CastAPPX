package com.android.castappx.selfDesignView

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** Keep the same messages above the input bar when the keyboard resizes the viewport. */
class ChatScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    private var scrollAfterResize: Int? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Capture the offset before ScrollView clamps it to the new viewport.
        if (oldh > 0 && h != oldh) {
            scrollAfterResize = scrollY + oldh - h
        }
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        scrollAfterResize?.let { offset ->
            scrollTo(0, offset)
            scrollAfterResize = null
        }
    }
}
