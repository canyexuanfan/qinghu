package top.hnwen17.guard.core.session

/**
 * 窗口会话身份（QH-P04-04）。
 *
 * 一次跨应用窗口会话由 user/package/windowId/epoch 唯一确定：
 * - [epoch] 由单调时钟生成（见 [MonotonicClock]），不使用墙钟；
 * - className 只是提示性信息（可能指向中间 Activity），不作为身份；
 * - 窗口切换 → 新 epoch，旧 epoch 的全部在途结果自动作废；
 * - 墙钟回拨不影响 TTL 计算（比较只用单调时钟）。
 */
data class WindowSession(
    val userId: Int,
    val packageName: String,
    val windowId: Int,
    val epoch: Long,
    val classNameHint: String = ""
) {
    fun sameWindow(other: WindowSession): Boolean =
        userId == other.userId && packageName == other.packageName && windowId == other.windowId
}

/** 注入式单调时钟：生产用 System.nanoTime，测试用 Fake 手动推进。 */
fun interface MonotonicClock {
    fun nowMs(): Long
}

/** 墙钟（仅用于展示/落盘时间戳，绝不参与 TTL/超时判断）。 */
fun interface WallClock {
    fun nowMs(): Long
}

/**
 * 纪元管理器：识别窗口切换并发放新纪元。
 * 线程约束：所有比较基于单调时钟；同类窗口只有收到"不同会话"事件才翻新纪元。
 */
class WindowEpochTracker(private val clock: MonotonicClock) {

    private var current: WindowSession? = null

    @Synchronized
    fun onWindowEvent(session: WindowSession): EpochChange {
        val previous = current
        return if (previous == null || !previous.sameWindow(session)) {
            current = session
            if (previous == null) EpochChange.NEW(session)
            else EpochChange.SWITCHED(previous, session)
        } else {
            // 同一窗口的重复事件：保持纪元不变，避免旧结果被误杀
            EpochChange.UNCHANGED(session)
        }
    }

    @Synchronized
    fun currentEpoch(): Long = current?.epoch ?: NO_EPOCH

    /** 判断结果是否仍对当前纪元有效；无会话时一律无效。 */
    @Synchronized
    fun isCurrent(session: WindowSession): Boolean = current == session

    @Synchronized
    fun invalidate() {
        current = null
    }

    sealed class EpochChange {
        abstract val session: WindowSession

        class NEW(override val session: WindowSession) : EpochChange()
        class SWITCHED(val old: WindowSession, override val session: WindowSession) : EpochChange()
        class UNCHANGED(override val session: WindowSession) : EpochChange()
    }

    companion object {
        const val NO_EPOCH = -1L

        /** 工厂：用单调时钟时间作为初始纪元（重复概率可忽略，且只增不回退）。 */
        fun newEpoch(session: WindowSession, clock: MonotonicClock): WindowSession =
            session.copy(epoch = clock.nowMs())
    }
}
