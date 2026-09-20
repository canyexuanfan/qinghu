package top.hnwen17.guard.platform.sensor

import top.hnwen17.guard.core.capability.SafetyGate
import top.hnwen17.guard.core.session.MonotonicClock

/**
 * Sensor 防摇一摇决策核心（QH-P10 安全门，纯逻辑可测）。
 *
 * 合同（PRD SENSOR-01~03 / ADR 待补）：
 * - 抑制会话：识别到疑似摇一摇触发模式后，在时间窗内抑制"手机动作触发广告"的资格；
 * - 安全门：没有 Shizuku/系统级抑制能力证明时，standard 一律 NO_GO——
 *   本决策器只输出"建议抑制会话"，**不宣称对目标 App 的真实抑制生效**；
 * - 判定依据保守：短窗内峰值方差超阈值才算候选，单次超阈值不触发；
 * - 无传感器设备：noSensors → 会话不启动（跳过）。
 */
class SensorGate(private val clock: MonotonicClock) {

    data class Config(
        val peakThreshold: Float = 12.0f,     // 合成加速度峰值 m/s²（自由落体≈9.8，摇动通常>12）
        val windowMs: Long = 1200L,           // 峰值统计窗口
        val peaksToArm: Int = 2,              // 窗口内峰值次数≥2 才武装
        val sessionMs: Long = 2500L,          // 武装后的抑制会话时长
        val requireForeground: Boolean = true // 仅前台采样（后台不判定）
    )

    data class State(
        val gate: SafetyGate = SafetyGate.NOT_APPLICABLE,
        val peaksInWindow: Int = 0,
        val lastPeakAtMs: Long = 0L,
        val sessionUntilMs: Long = 0L,
        val sensorsAvailable: Boolean = true
    )

    /** 采样喂入：返回当前是否处于抑制会话中（调用方据此在事件入口丢弃动作触发类信号）。 */
    fun onAccelerometer(state: State, magnitude: Float, nowMs: Long): Pair<State, Boolean> {
        if (!state.sensorsAvailable) return state to false
        if (nowMs < state.sessionUntilMs) return state to true
        val peaks = state.peaksInWindow
        val inWindow = nowMs - state.lastPeakAtMs <= clockConfig.windowMs
        val newPeaks = if (magnitude >= clockConfig.peakThreshold) {
            if (inWindow) peaks + 1 else 1
        } else if (inWindow) peaks else 0
        val lastPeak = if (magnitude >= clockConfig.peakThreshold) nowMs else state.lastPeakAtMs
        val armed = newPeaks >= clockConfig.peaksToArm
        val next = state.copy(
            peaksInWindow = newPeaks,
            lastPeakAtMs = lastPeak,
            sessionUntilMs = if (armed) nowMs + clockConfig.sessionMs else state.sessionUntilMs
        )
        return next to armed
    }

    /** 安全门评估：桥接/系统能力未证明 → NO_GO（standard 抑制不生效，只做本进程内事件丢弃）。 */
    fun evaluateGate(hasSystemSuppressionProof: Boolean, sensorsAvailable: Boolean): SafetyGate = when {
        !sensorsAvailable -> SafetyGate.NO_GO
        hasSystemSuppressionProof -> SafetyGate.GO
        else -> SafetyGate.NO_GO
    }

    private val clockConfig get() = Config()
}
