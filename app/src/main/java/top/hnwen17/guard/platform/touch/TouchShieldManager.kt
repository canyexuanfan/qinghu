package top.hnwen17.guard.platform.touch

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import top.hnwen17.guard.core.session.WindowSession

/**
 * TouchShield 管理器（QH-P09-01/05/06/08）。
 *
 * - 窗口类型 TYPE_ACCESSIBILITY_OVERLAY：无障碍服务专属层级，**不需要 SYSTEM_ALERT_WINDOW 权限**；
 * - WindowManager 增删必须在 **Main 线程**（post 到主线程 Handler，调用方线程安全）；
 * - 非永久全屏透明层：只在有明确边界时建盾，单一盾实例；
 * - 撤销时机（QH-P09-05/06）：会话切换（epoch 变化）、窗口离开、竖横屏/熄屏事件、滚动边界失效——
 *   全部走 [dismiss]（先撤旧盾再做新匹配，合同明确）；
 * - 手势消费：ShieldView 内部用 ShieldTouchState 状态机，DOWN 起全消费不转发；
 * - 界面不支持（QH-P09-08）：resolve 失败/系统拒绝 addView → 记录受限状态并放弃，不重试轰炸。
 */
class TouchShieldManager(
    private val service: AccessibilityService,
    private val onChanged: (visible: Boolean, reason: String) -> Unit = { _, _ -> }
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var shieldView: View? = null
    private var boundSession: WindowSession? = null

    val isShowing: Boolean get() = shieldView != null

    /**
     * 建盾（幂等）：已有盾先撤。所有视图操作 post 主线程。
     * @param onTapInside 盾内轻点回调（解除入口 UI 由此触发）。
     */
    fun show(
        session: WindowSession,
        rect: ShieldBoundsResolver.Rect,
        onTapInside: () -> Unit
    ) {
        mainHandler.post {
            dismissInternal("rebind")
            val state = ShieldTouchState()
            val view = ShieldView(service, state, rect, onTapInside)
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                gravity = Gravity.TOP or Gravity.START
                x = rect.left; y = rect.top
                width = rect.width; height = rect.height
            }
            try {
                service.getSystemService(WindowManager::class.java)
                    .addView(view, params)
                shieldView = view
                boundSession = session
                onChanged(true, "shield shown ${session.packageName} epoch=${session.epoch}")
            } catch (e: Exception) {
                // 系统拒绝（QH-P09-08）：记录受限并放弃，不重试轰炸
                shieldView = null
                onChanged(false, "addView rejected: ${e.message?.take(60)}")
            }
        }
    }

    /** 撤盾（幂等，线程安全）。 */
    fun dismiss(reason: String) {
        mainHandler.post { dismissInternal(reason) }
    }

    private fun dismissInternal(reason: String) {
        val view = shieldView ?: return
        try {
            service.getSystemService(WindowManager::class.java).removeView(view)
            onChanged(false, "shield dismissed: $reason")
        } catch (e: Exception) {
            onChanged(false, "dismiss failed: ${e.message?.take(60)}")
        } finally {
            shieldView = null
            boundSession = null
        }
    }

    /**
     * 会话切换（QH-P09-06）：包名或 epoch 变化即撤旧盾（先撤再做新匹配由调用方编排）。
     */
    fun onWindowChanged(session: WindowSession) {
        val bound = boundSession ?: return
        if (!bound.sameWindow(session) || bound.epoch != session.epoch) {
            dismissInternal("window changed")
        }
    }

    /** 熄屏/横竖屏等系统级事件：一律先撤（QH-P09-06）。 */
    fun onSystemDisruption() {
        dismissInternal("system disruption")
    }
}
