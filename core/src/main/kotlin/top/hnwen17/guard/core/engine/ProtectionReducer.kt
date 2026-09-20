package top.hnwen17.guard.core.engine

import top.hnwen17.guard.core.Capability
import top.hnwen17.guard.core.policy.PolicyResolver
import top.hnwen17.guard.core.policy.ResolvedPolicy
import top.hnwen17.guard.core.TriPolicy
import top.hnwen17.guard.core.records.EventType
import top.hnwen17.guard.core.records.ProtectionOutcome
import top.hnwen17.guard.core.session.MonotonicClock

/**
 * 确定性 Reducer（QH-P04-06）。
 *
 * 纯函数：`(State, GuardEvent) -> (State, List<CandidateAction>)`。
 * - 不做 IO、不起线程、不查节点；副作用由执行端执行候选动作；
 * - 会话/纪元转移内聚：事件属于新会话时自动翻新纪元（纪元=事件单调时刻）并重置状态；
 * - 注入 [MonotonicClock]（测试注入 Fake）；重复事件、乱序、暂停、恢复输出确定，可重放。
 */
class ProtectionReducer(
    private val policyInput: PolicyInput,
    private val clock: MonotonicClock
) {

    /** 决策所需输入快照：由调用方提供，core 不主动读取设置存储。 */
    data class PolicyInput(
        val globalPaused: Boolean,
        val globalEnabled: Set<Capability>,
        val sensitivePackages: Set<String> = emptySet(),
        val appTri: Map<String, Map<Capability, TriPolicy>> = emptyMap(),
        val legacyPolicies: Map<String, top.hnwen17.guard.core.AppPolicy> = emptyMap()
    )

    /** 引擎内状态：只含 primitive 与小型摘要，不可变、可整体替换。 */
    data class State(
        val sessionEpoch: Long = NO_EPOCH,
        val paused: Boolean = false,
        val pendingWindowEvents: Int = 0,
        val activeMasks: Set<Capability> = emptySet(),
        val completedSinceResume: Int = 0
    )

    /** 候选动作：执行端按此执行，Reducer 不执行。 */
    sealed class CandidateAction {
        abstract val capability: Capability
        abstract val windowEpoch: Long

        data class CloseAd(override val windowEpoch: Long, override val capability: Capability = Capability.CLEANER) : CandidateAction()
        data class MaskRegion(override val windowEpoch: Long, override val capability: Capability = Capability.TOUCH) : CandidateAction()
        data class StartSensorSession(override val windowEpoch: Long, override val capability: Capability = Capability.SENSOR) : CandidateAction()
        data class BlockLaunch(override val windowEpoch: Long, val targetPackage: String, override val capability: Capability = Capability.JUMP) : CandidateAction()
        data class RecordOnly(override val windowEpoch: Long, val type: EventType, val outcome: ProtectionOutcome, override val capability: Capability = Capability.CLEANER) : CandidateAction()
    }

    fun reduce(state: State, event: GuardEvent): Pair<State, List<CandidateAction>> {
        // 控制事件优先于一切业务事件（安全停止/暂停不被业务洪泛淹没）
        if (event is GuardEvent.Control) return reduceControl(state, event)

        // 会话转移：事件纪元与状态不同（首次/窗口切换）→ 翻新纪元 + 干净状态
        val eventEpoch = event.session.epoch
        var next = if (state.sessionEpoch == NO_EPOCH || state.sessionEpoch != eventEpoch) {
            State(sessionEpoch = eventEpoch, paused = state.paused)
        } else {
            state
        }

        var actions: List<CandidateAction> = emptyList()
        when (event) {
            is GuardEvent.WindowChanged -> {
                next = next.copy(pendingWindowEvents = (next.pendingWindowEvents + 1).coerceAtMost(MAX_PENDING))
                actions = decideForWindow(next, event)
            }
            is GuardEvent.UserAction ->
                // 用户主动交互：遮罩类保护让位（不与用户抢屏幕）
                next = next.copy(activeMasks = emptySet())
            is GuardEvent.Suggestion -> actions = decideForSuggestion(next, event)
            is GuardEvent.LaunchObserved -> actions = decideForLaunch(next, event)
            is GuardEvent.Lifecycle -> Unit
            is GuardEvent.Control -> Unit // 已在上方处理
        }
        return next to actions
    }

    private fun reduceControl(state: State, event: GuardEvent.Control): Pair<State, List<CandidateAction>> {
        val next = when (event.command) {
            GuardEvent.Control.Command.PAUSE -> state.copy(paused = true)
            GuardEvent.Control.Command.RESUME -> state.copy(paused = false)
            GuardEvent.Control.Command.STOP_ALL -> State() // 全清：安全停止优先于所有新动作
        }
        return next to emptyList()
    }

    private fun decideForWindow(state: State, event: GuardEvent.WindowChanged): List<CandidateAction> {
        val resolved = resolvedFor(event.session.packageName)
        return buildList {
            if (resolved.cleaner) add(CandidateAction.CloseAd(state.sessionEpoch))
            if (resolved.touch) add(CandidateAction.MaskRegion(state.sessionEpoch))
        }
    }

    private fun decideForSuggestion(state: State, event: GuardEvent.Suggestion): List<CandidateAction> {
        val resolved = resolvedFor(event.session.packageName)
        if (!resolved[event.capability]) return emptyList()
        return when (event.kind) {
            GuardEvent.Suggestion.Kind.CLOSE_BUTTON_SEEN,
            GuardEvent.Suggestion.Kind.COUNTDOWN_SEEN ->
                if (resolved.cleaner) listOf(CandidateAction.CloseAd(state.sessionEpoch)) else emptyList()
            GuardEvent.Suggestion.Kind.RISK_REGION_SEEN ->
                if (resolved.touch) listOf(CandidateAction.MaskRegion(state.sessionEpoch)) else emptyList()
            GuardEvent.Suggestion.Kind.SENSOR_TRIGGERED ->
                // Sensor 安全门在 P10/P11；此处只提候选，执行端按 CapabilityState 平台门决定
                if (resolved.sensor) listOf(CandidateAction.StartSensorSession(state.sessionEpoch)) else emptyList()
            GuardEvent.Suggestion.Kind.LAUNCH_SEEN -> emptyList() // 跳转走 LaunchObserved 专用路径
        }
    }

    private fun decideForLaunch(state: State, event: GuardEvent.LaunchObserved): List<CandidateAction> {
        val resolved = resolvedFor(event.session.packageName)
        if (!resolved.jump) {
            return listOf(CandidateAction.RecordOnly(state.sessionEpoch, EventType.LAUNCH_INTENT_OBSERVED, ProtectionOutcome.REQUESTED))
        }
        return if (event.sensitive) {
            // 敏感流程（支付/登录）：意图未知放行、不重放；落 UNKNOWN 而非冒充拦截成功
            listOf(CandidateAction.RecordOnly(state.sessionEpoch, EventType.LAUNCH_INTENT_AMBIGUOUS_ALLOWED, ProtectionOutcome.UNKNOWN))
        } else {
            listOf(CandidateAction.BlockLaunch(state.sessionEpoch, event.targetPackage))
        }
    }

    private fun resolvedFor(packageName: String): ResolvedPolicy =
        PolicyResolver.resolve(
            globalPaused = policyInput.globalPaused,
            globalEnabled = policyInput.globalEnabled,
            appId = packageName,
            appLegacy = policyInput.legacyPolicies[packageName],
            appTri = policyInput.appTri[packageName] ?: emptyMap(),
            sensitive = packageName in policyInput.sensitivePackages
        )

    companion object {
        const val NO_EPOCH = -1L

        /** 待处理窗口事件计数上限：洪泛下封顶（丢弃由调度器先进先出保证）。 */
        const val MAX_PENDING = 8
    }
}
