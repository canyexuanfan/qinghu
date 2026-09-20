package top.hnwen17.guard.core.engine

import top.hnwen17.guard.core.Capability
import top.hnwen17.guard.core.records.EventType
import top.hnwen17.guard.core.session.WindowSession

/**
 * 轻量事件协议（QH-P04-05）。
 *
 * 硬约束：事件只携带 primitive 与小型摘要字符串（有长度上限），
 * 禁止把 AccessibilityNodeInfo/Activity/Bitmap/View 塞进事件、状态或队列。
 * 事件是跨线程、跨生命周期传递的数据，必须与 UI 生命周期零引用。
 */
sealed class GuardEvent {

    abstract val session: WindowSession
    /** 单调时钟毫秒（采集时刻），由核心统一处理时序，不信任发送方墙钟。 */
    abstract val atMonotonicMs: Long

    /** 窗口内容变化（无障碍 TYPE_WINDOW_CONTENT_CHANGED 等的廉价抽象）。 */
    data class WindowChanged(
        override val session: WindowSession,
        override val atMonotonicMs: Long,
        val eventTypes: Int,
        val changeHint: ChangeHint = ChangeHint.NONE
    ) : GuardEvent()

    /** 摘要级窗口描述（标题/包名/类型），由采集端在异线程提取，core 不回查节点。 */
    data class ChangeHint(
        val windowType: Int = 0,
        val titleHash: Long = 0L,
        val flags: Int = 0
    ) {
        companion object {
            val NONE = ChangeHint()
        }
    }

    /** 用户显式动作（返回/点击/滚动的摘要信号）。 */
    data class UserAction(
        override val session: WindowSession,
        override val atMonotonicMs: Long,
        val action: Kind
    ) : GuardEvent() {
        enum class Kind { BACK_PRESSED, TOUCH_OUTSIDE, SCROLL, INTERACTION_OTHER }
    }

    /** 采集端建议（例如"发现疑似关闭按钮"，带坐标摘要而非节点引用）。 */
    data class Suggestion(
        override val session: WindowSession,
        override val atMonotonicMs: Long,
        val capability: Capability,
        val kind: Kind,
        val summary: String
    ) : GuardEvent() {
        enum class Kind { CLOSE_BUTTON_SEEN, COUNTDOWN_SEEN, RISK_REGION_SEEN, SENSOR_TRIGGERED, LAUNCH_SEEN }
        init {
            require(summary.length <= MAX_SUMMARY) { "summary too large" }
        }
    }

    /** 跳转请求摘要（Intent 的结构化摘要，不携带原始 Intent，禁止重放支付/登录）。 */
    data class LaunchObserved(
        override val session: WindowSession,
        override val atMonotonicMs: Long,
        val targetPackage: String,
        val sourcePackage: String,
        val intentCategoryHint: String,
        val sensitive: Boolean
    ) : GuardEvent() {
        init {
            require(intentCategoryHint.length <= MAX_SUMMARY)
        }
    }

    /** 能力/引擎生命周期事件。 */
    data class Lifecycle(
        override val session: WindowSession,
        override val atMonotonicMs: Long,
        val type: EventType,
        val detail: String = ""
    ) : GuardEvent() {
        init {
            require(detail.length <= MAX_SUMMARY) { "detail too large" }
        }
    }

    /** 引擎控制：安全停止必须走独立优先通道，不允许被队列丢弃。 */
    data class Control(
        override val session: WindowSession,
        override val atMonotonicMs: Long,
        val command: Command
    ) : GuardEvent() {
        enum class Command { PAUSE, RESUME, STOP_ALL }
    }

    companion object {
        /** 摘要长度上限：事件协议拒绝大对象变形涌入。 */
        const val MAX_SUMMARY = 120
    }
}
