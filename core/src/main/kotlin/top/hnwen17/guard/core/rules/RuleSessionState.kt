package top.hnwen17.guard.core.rules

import top.hnwen17.guard.core.session.MonotonicClock

/**
 * 规则会话状态（QH-P07-09）。
 *
 * 单会话内每条规则的执行账本：
 * - 最多 [RuleLimits.MAX_ATTEMPTS] 次（默认2）主动尝试；
 * - 每次之间强制 [UiRule.RuleAction.cooldownMs] 冷却（单调时钟，墙钟回拨免疫）；
 * - 后验超时前不重复执行；
 * - 会话切换（epoch 变化）整体重置——[onSessionSwitch]。
 * 线程：仅规则工作器协程访问，无需加锁。
 */
class RuleSessionState(private val clock: MonotonicClock) {

    /** ruleId → 执行账本。 */
    private val ledgers = HashMap<String, Ledger>()

    private data class Ledger(
        var attempts: Int = 0,
        var lastAttemptAtMs: Long = 0L,
        var postconditionDeadlineMs: Long = 0L,
        var satisfied: Boolean = false
    )

    /**
     * 是否允许对该规则执行一次主动动作。
     * @param cooldownMs 规则自带冷却（毫秒）。
     */
    fun canAttempt(rule: UiRule, cooldownMs: Long): Boolean {
        val ledger = ledgers[rule.id] ?: return true
        if (ledger.satisfied) return false
        if (ledger.attempts >= rule.action.maxAttempts) return false
        val now = clock.nowMs()
        if (now - ledger.lastAttemptAtMs < cooldownMs) return false
        if (now < ledger.postconditionDeadlineMs) return false // 后验观察期内不重复
        return true
    }

    /** 记录一次主动尝试；同时开启后验观察窗（若有）。 */
    fun recordAttempt(rule: UiRule, cooldownMs: Long, postconditionTimeoutMs: Long) {
        val now = clock.nowMs()
        val ledger = ledgers.getOrPut(rule.id) { Ledger() }
        ledger.attempts++
        ledger.lastAttemptAtMs = now
        if (rule.postcondition != null) {
            ledger.postconditionDeadlineMs = now + (postconditionTimeoutMs.takeIf { it > 0 } ?: rule.postcondition.timeoutMs)
        } else {
            ledger.postconditionDeadlineMs = now + cooldownMs
        }
    }

    /** 后验确认成功：规则在本会话内完成，不再触发。 */
    fun markSatisfied(rule: UiRule) {
        ledgers[rule.id]?.satisfied = true
    }

    /** 当前是否处于某规则的后验观察期。 */
    fun inPostconditionWindow(ruleId: String): Boolean {
        val ledger = ledgers[ruleId] ?: return false
        return clock.nowMs() < ledger.postconditionDeadlineMs
    }

    /** 会话切换：整体重置（防跨会话账本泄漏导致的"不再点击"或"无限点击"）。 */
    fun onSessionSwitch() = ledgers.clear()

    /** 诊断用：活跃账本数（有界：≤命中规则数）。 */
    fun activeLedgers(): Int = ledgers.size
}
