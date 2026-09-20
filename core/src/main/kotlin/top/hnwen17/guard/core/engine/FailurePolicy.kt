package top.hnwen17.guard.core.engine

import top.hnwen17.guard.core.capability.CapabilityState
import top.hnwen17.guard.core.session.MonotonicClock

/**
 * 错误熔断与恢复（QH-P04-11）。
 *
 * - 指数退避 + 次数上限的有界重试：`delay = base * 2^(n-1)`，封顶 [maxDelayMs]；
 * - 权限类失败（用户撤销授权）不重试：直接退出对应能力并标 NEEDS_PERMISSION；
 * - 连续失败达到 [circuitThreshold] 进入熔断（CIRCUIT_OPEN），冷却期内不再尝试；
 * - 恢复未完成时保留 CIRCUIT_OPEN 状态单独标识，绝不悄悄清零成"从未失败"。
 */
class FailurePolicy(
    private val clock: MonotonicClock,
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 60_000,
    private val maxAttempts: Int = 3,
    private val circuitThreshold: Int = 5,
    private val cooldownMs: Long = 5 * 60_000
) {

    data class CircuitState(
        val consecutiveFailures: Int = 0,
        val circuitOpenSinceMs: Long = 0L,
        val lastDelayMs: Long = 0L,
        val opened: Boolean = false
    ) {
        val isRecovering: Boolean get() = opened
    }

    fun onAttemptFailed(state: CircuitState, isPermissionError: Boolean): CircuitState {
        if (isPermissionError) {
            // 权限失效：不重试，交由上层把 CapabilityState.request/platform 调整并提示授权
            return state.copy(opened = true, circuitOpenSinceMs = clock.nowMs(), lastDelayMs = 0L)
        }
        val failures = state.consecutiveFailures + 1
        val delay = (baseDelayMs shl (failures - 1).coerceAtMost(6)).coerceAtMost(maxDelayMs)
        return if (failures >= circuitThreshold) {
            state.copy(consecutiveFailures = failures, opened = true, circuitOpenSinceMs = clock.nowMs(), lastDelayMs = delay)
        } else {
            state.copy(consecutiveFailures = failures, lastDelayMs = delay)
        }
    }

    fun onAttemptSucceeded(state: CircuitState): CircuitState =
        CircuitState() // 成功即完全复位：正常态不留失败痕迹（历史在记录层，不在运行态）

    /** 熔断是否允许再次尝试；冷却期满自动半开，允许一次探测。 */
    fun canAttempt(state: CircuitState, nowMs: Long = clock.nowMs()): Boolean =
        !state.opened || (nowMs - state.circuitOpenSinceMs) >= cooldownMs

    /**
     * 冷却期满后的半开探测结果处理：成功走 [onAttemptSucceeded]，
     * 失败重新熔断（新的冷却周期），避免高频反复弹窗/重启服务。
     */
    fun onProbeResult(state: CircuitState, success: Boolean): CircuitState =
        if (success) onAttemptSucceeded(state)
        else CircuitState(consecutiveFailures = 1, opened = true, circuitOpenSinceMs = clock.nowMs(), lastDelayMs = baseDelayMs)

    /** 能力状态转移辅助：失败后 runtime 的下一状态。 */
    fun nextRuntime(state: CircuitState, current: CapabilityState.Runtime): CapabilityState.Runtime =
        when {
            state.opened -> CapabilityState.Runtime.CIRCUIT_OPEN
            state.consecutiveFailures > 0 -> CapabilityState.Runtime.CONNECTING
            else -> current
        }
}
