package top.hnwen17.guard.platform.touch

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * 护罩视图（QH-P09-03/04）：
 * - 绘制轻提示（半透明遮罩+边框），不做任何广告样式；
 * - **消费全部触摸事件**（onTouchEvent 恒返回 true），不把部分事件转发底层；
 * - 用户点"解除"按钮 → state.requestDismiss()；手势 UP/CANCEL 后由状态机判定移除。
 */
class ShieldView(
    context: Context,
    private val state: ShieldTouchState,
    private val rect: ShieldBoundsResolver.Rect,
    private val onTapInside: () -> Unit
) : View(context) {

    private val scrim = Paint().apply {
        color = Color.argb(60, 30, 136, 229) // 轻提示蓝，非全黑
        style = Paint.Style.FILL
    }
    private val border = Paint().apply {
        color = Color.argb(180, 30, 136, 229)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val dismissBtn = Rect() // "解除"按钮区（视图右上角），布局时计算

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val btn = 96 // px 近似 48dp@2x
        dismissBtn.set(w - btn - 16, 16, w - 16, 16 + btn)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border)
        val p = Paint().apply { color = Color.WHITE; textSize = 36f; isAntiAlias = true }
        canvas.drawText("已保护", 24f, height - 40f, p)
        canvas.drawText("解除", dismissBtn.left.toFloat() + 18f, dismissBtn.centerY().toFloat() + 12f, p)
        canvas.drawRect(dismissBtn, border)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                state.onDown(1)
                // DOWN 落在"解除"入口：请求解除（手势终止时生效）
                if (dismissBtn.contains(event.x.toInt(), event.y.toInt())) state.requestDismiss()
                if (rect.contains(rect.left + event.x.toInt(), rect.top + event.y.toInt())) onTapInside()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> state.onPointerDown(event.pointerCount)
            MotionEvent.ACTION_MOVE -> state.onMove()
            MotionEvent.ACTION_POINTER_UP -> state.onPointerUp(event.pointerCount - 1)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val remove = state.onUpOrCancel(allPointersUp = true)
                return true // 无论是否移除，本手势已完整消费
            }
        }
        return true
    }
}
