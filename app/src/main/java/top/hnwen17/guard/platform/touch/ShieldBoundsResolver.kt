package top.hnwen17.guard.platform.touch

import top.hnwen17.guard.core.session.WindowSession

/**
 * 护罩边界解析（QH-P09-02）。
 *
 * 输入：目标节点在**窗口坐标系**的 bounds（无障碍 getBoundsInScreen 已含状态栏偏移）
 * 与窗口可见区域；输出裁剪后的护罩矩形。
 * 规则：
 * - 与可见区域取交集（裁掉屏幕外部分）；
 * - 排除 IME 占用区（键盘弹出时护罩不得盖住输入法，避免误导触）；
 * - 面积过小（<最小阈值）视为无法可靠遮盖 → 拒绝建盾；
 * - 结果绑定 windowEpoch，边界失效（滚动/切窗）由调用方按 epoch 校验后撤销。
 */
object ShieldBoundsResolver {

    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
        val area: Int get() = width * height
        fun contains(x: Int, y: Int): Boolean =
            x in left until right && y in top until bottom
    }

    data class Resolved(val rect: Rect, val session: WindowSession)

    const val MIN_SIDE_DP = 48

    fun resolve(
        nodeBoundsInScreen: Rect,
        windowVisibleFrame: Rect,
        imeTop: Int?,
        session: WindowSession,
        densityDpi: Int
    ): Resolved? {
        // 与窗口可见区取交集
        val l = maxOf(nodeBoundsInScreen.left, windowVisibleFrame.left)
        val t = maxOf(nodeBoundsInScreen.top, windowVisibleFrame.top)
        val r = minOf(nodeBoundsInScreen.right, windowVisibleFrame.right)
        val b = minOf(nodeBoundsInScreen.bottom, windowVisibleFrame.bottom)
        if (r <= l || b <= t) return null
        // IME 排除：与键盘区重叠则裁掉下半（不能兼顾滚动的版式由调用方禁用盾，见合同）
        val clippedBottom = if (imeTop != null && imeTop in t until b) imeTop else b
        val rect = Rect(l, t, r, clippedBottom)
        if (rect.area <= 0) return null
        // 最小尺寸（dp→px）：过小区域不可靠遮盖
        val minSidePx = MIN_SIDE_DP * densityDpi / 160
        if (rect.width < minSidePx || rect.height < minSidePx) return null
        return Resolved(rect, session)
    }
}
