package top.hnwen17.guard.platform.touch

/**
 * 护罩手势状态机（QH-P09-03/04）。
 *
 * 合同：
 * - 消费完整 down/move/up/cancel 序列，**绝不把部分事件转发到底层**；
 * - 多指：任一指针抬起视为手势未结束，全部抬起才结算；
 * - 解除请求（用户点"解除"）：记 pendingDismiss，等待**当前手势 UP/CANCEL** 后真正移除；
 *   用户需要新的点击才能穿透（旧手势绝不复活穿透）；
 * - 状态可整体替换（纯逻辑，注入时钟不是必需——状态迁移不依赖时间）。
 */
class ShieldTouchState {

    enum class Phase { IDLE, GESTURE_ACTIVE, PENDING_DISMISS }

    var phase: Phase = Phase.IDLE
        private set

    /** 当前按下的指针数。 */
    var pointers: Int = 0
        private set

    /** 是否发生过移动（区分轻点与拖动，供诊断）。 */
    var moved: Boolean = false
        private set

    /** 收到 DOWN：进入手势态（若处于待解除，手势继续被消费，解除推迟到 UP）。 */
    fun onDown(pointerCount: Int) {
        pointers = pointerCount.coerceAtLeast(1)
        moved = false
        phase = Phase.GESTURE_ACTIVE
    }

    fun onPointerDown(totalPointers: Int) {
        pointers = totalPointers.coerceAtLeast(pointers)
    }

    fun onMove() {
        if (phase == Phase.GESTURE_ACTIVE) moved = true
    }

    fun onPointerUp(remainingPointers: Int) {
        pointers = remainingPointers.coerceAtLeast(0)
    }

    /**
     * @return true=护罩应当移除（手势已终止且处于待解除态）；false=继续消费。
     */
    fun onUpOrCancel(allPointersUp: Boolean): Boolean {
        val shouldRemove = phase == Phase.PENDING_DISMISS && allPointersUp
        if (allPointersUp) {
            phase = if (shouldRemove) Phase.IDLE else Phase.IDLE
            pointers = 0
        }
        return shouldRemove
    }

    /** 用户点击"解除"入口：进入待解除；本手势结束前盾仍在消费。 */
    fun requestDismiss(): Boolean {
        if (phase != Phase.GESTURE_ACTIVE && phase != Phase.IDLE) return false
        phase = Phase.PENDING_DISMISS
        return true
    }

    /** 强制重置（窗口切换/停用）：不经过手势语义，直接清空。 */
    fun reset() {
        phase = Phase.IDLE
        pointers = 0
        moved = false
    }
}
