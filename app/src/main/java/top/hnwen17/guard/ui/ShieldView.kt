package top.hnwen17.guard.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** Approved static illustration. No draw-loop, timer, particle effect or redraw allocation. */
class ShieldView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : AppCompatImageView(context, attrs, defStyleAttr) {
    var muted: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            imageAlpha = if (value) 120 else 255
        }
}
