package top.hnwen17.guard.core.records

import top.hnwen17.guard.core.Capability

/**
 * 可验证结果分级（QH-P04-02）。
 *
 * REQUESTED  仅记录"已请求"，不构成任何拦截成绩；
 * EXECUTED   本程序确认已执行动作（如发出关闭点击、建立遮罩）；
 * VERIFIED   有后验证据确认生效（节点消失、会话取消等）；
 * FAILED     执行或后验失败；
 * UNKNOWN    无法证实，宁可 UNKNOWN 也不冒充 VERIFIED。
 *
 * 统计口径硬约束：
 * - Sensor 会话抑制 ≠ 广告阻止，单独记 SENSOR_SESSION；
 * - DNS 拒绝 ≠ 广告拦截，单独记 DNS_DENIED，不与 CLEANER 混加；
 * - preview 示例数据永远带 sample 标记，不进 standard 真实统计。
 */
enum class ProtectionOutcome {
    REQUESTED, EXECUTED, VERIFIED, FAILED, UNKNOWN,
    /** QH-P13：跳转已实际拦截（BACK 已执行），属真实执行动作。 */
    START_REJECTED
}

/** 各能力的事件类型：事件语义互斥，禁止跨能力复用同一事件冒充。 */
enum class EventType {
    // Cleaner（广告净化）
    AD_WINDOW_DETECTED, AD_CLOSE_CLICKED, AD_WINDOW_CLOSED_VERIFIED, AD_CLOSE_FAILED, AD_WINDOW_EXPIRED,

    // TouchShield（误触防护）
    RISK_REGION_MASKED, MASK_DISMISSED_BY_USER, TOUCH_BLOCK_FAILED,

    // Sensor（防摇一摇）：会话是"抑制会话"，不是拦截成功
    SENSOR_SESSION_STARTED, SENSOR_SESSION_EXTENDED, SENSOR_SESSION_ENDED, SENSOR_PROBE_FAILED,
    /** QH-P18：无障碍通道检测到广告落地页并自动返回（计入 SENSOR 能力记录）。 */
    SENSOR_AD_BACKED,

    // Jump（跳转防护）
    LAUNCH_INTENT_OBSERVED, LAUNCH_ALLOWED_USER_VISIBLE, LAUNCH_BLOCKED_EXECUTED, LAUNCH_BLOCK_FAILED, LAUNCH_INTENT_AMBIGUOUS_ALLOWED,

    // DNS（网络）
    DNS_DENIED, DNS_ALLOWED, DNS_ENGINE_ERROR,

    // 生命周期/系统
    CAPABILITY_LOST, CAPABILITY_RECOVERED, ENGINE_STARTED, ENGINE_STOPPED, UNKNOWN
}

/** 单条可持久化保护记录（协议级，primitive-only，见 GuardEvent 约束）。 */
data class ProtectionEvent(
    val id: Long,
    val appId: String,
    val capability: Capability,
    val type: EventType,
    val outcome: ProtectionOutcome,
    val atMonotonicMs: Long,
    val atEpochMs: Long,
    val windowEpoch: Long,
    val detail: String = "",
    val sample: Boolean = false
) {
    init {
        require(windowEpoch >= 0) { "windowEpoch must be non-negative" }
    }

    companion object {
        /** Sensor 会话结束不得计入"已执行"成绩的守门断言。 */
        fun isCountableProtection(e: ProtectionEvent): Boolean = when (e.type) {
            EventType.SENSOR_SESSION_STARTED, EventType.SENSOR_SESSION_EXTENDED, EventType.SENSOR_SESSION_ENDED,
            EventType.LAUNCH_INTENT_OBSERVED, EventType.LAUNCH_ALLOWED_USER_VISIBLE,
            EventType.DNS_ALLOWED, EventType.UNKNOWN -> false
            else -> e.outcome == ProtectionOutcome.EXECUTED || e.outcome == ProtectionOutcome.VERIFIED
        }
    }
}
