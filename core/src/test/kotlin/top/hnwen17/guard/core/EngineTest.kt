package top.hnwen17.guard.core

import top.hnwen17.guard.core.engine.ActionArbiter
import top.hnwen17.guard.core.engine.FailurePolicy
import top.hnwen17.guard.core.engine.GuardEvent
import top.hnwen17.guard.core.engine.ProtectionReducer
import top.hnwen17.guard.core.records.EventType
import top.hnwen17.guard.core.records.ProtectionOutcome
import top.hnwen17.guard.core.session.MonotonicClock
import top.hnwen17.guard.core.session.WindowSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val SESSION = WindowSession(0, "com.target", 1, epoch = 100L)

private fun reducer(allCaps: Boolean = true, sensitive: Boolean = false, paused: Boolean = false) =
    ProtectionReducer(
        ProtectionReducer.PolicyInput(
            globalPaused = paused,
            globalEnabled = if (allCaps) Capability.entries.toSet() else emptySet(),
            sensitivePackages = if (sensitive) setOf("com.target") else emptySet()
        ),
        MonotonicClock { 0L }
    )

/** QH-P04-05/06：事件协议约束与确定性 Reducer。 */
class ProtectionReducerTest {

    private fun windowEvent(epoch: Long = SESSION.epoch) =
        GuardEvent.WindowChanged(SESSION.copy(epoch = epoch), atMonotonicMs = 1L, eventTypes = 1)

    @Test
    fun `窗口事件在策略开启时产生关闭与遮罩候选`() {
        val (state, actions) = reducer().reduce(ProtectionReducer.State(), windowEvent())
        assertEquals(100L, state.sessionEpoch)
        assertEquals(2, actions.size)
        assertTrue(actions.any { it is ProtectionReducer.CandidateAction.CloseAd })
        assertTrue(actions.any { it is ProtectionReducer.CandidateAction.MaskRegion })
    }

    @Test
    fun `重复事件输出确定（可重放）`() {
        val r = reducer()
        var state = ProtectionReducer.State()
        repeat(10) {
            val (next, actions) = r.reduce(state, windowEvent())
            state = next
            assertEquals(1, actions.count { it is ProtectionReducer.CandidateAction.CloseAd })
        }
        assertEquals(ProtectionReducer.MAX_PENDING, state.pendingWindowEvents, "洪泛计数封顶")
    }

    @Test
    fun `全局暂停时不产生任何动作`() {
        val (_, actions) = reducer(paused = true).reduce(ProtectionReducer.State(), windowEvent())
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `STOP_ALL 清空全部状态，恢复后重新开始`() {
        val r = reducer()
        var state = ProtectionReducer.State()
        run { val (n, _) = r.reduce(state, windowEvent()); state = n }
        val (stopped, stopActions) = r.reduce(
            state,
            GuardEvent.Control(SESSION, 0L, GuardEvent.Control.Command.STOP_ALL)
        )
        assertEquals(ProtectionReducer.State(), stopped, "安全停止后状态归零")
        assertTrue(stopActions.isEmpty())
    }

    @Test
    fun `窗口切换翻新纪元，旧 pending 归零`() {
        val r = reducer()
        val (s1, _) = r.reduce(ProtectionReducer.State(), windowEvent(epoch = 100))
        val (s2, _) = r.reduce(s1, windowEvent(epoch = 200))
        assertEquals(200L, s2.sessionEpoch)
        assertEquals(1, s2.pendingWindowEvents, "新会话状态干净")
    }

    @Test
    fun `用户交互撤销遮罩，不与用户抢屏幕`() {
        val r = reducer()
        val (s1, _) = r.reduce(ProtectionReducer.State(), windowEvent())
        val s2 = s1.copy(activeMasks = setOf(Capability.TOUCH))
        val (s3, _) = r.reduce(
            s2,
            GuardEvent.UserAction(SESSION, 5L, GuardEvent.UserAction.Kind.BACK_PRESSED)
        )
        assertTrue(s3.activeMasks.isEmpty())
    }

    @Test
    fun `敏感流程跳转放行且落 UNKNOWN 不冒充拦截`() {
        val (_, actions) = reducer().reduce(
            ProtectionReducer.State(),
            GuardEvent.LaunchObserved(SESSION, 1L, "com.pay", "com.target", "PAYMENT", sensitive = true)
        )
        val record = actions.filterIsInstance<ProtectionReducer.CandidateAction.RecordOnly>().single()
        assertEquals(EventType.LAUNCH_INTENT_AMBIGUOUS_ALLOWED, record.type)
        assertEquals(ProtectionOutcome.UNKNOWN, record.outcome)
    }

    @Test
    fun `摘要超长的事件直接拒绝构造`() {
        val thrown = try {
            GuardEvent.Suggestion(SESSION, 1L, Capability.CLEANER,
                GuardEvent.Suggestion.Kind.CLOSE_BUTTON_SEEN, "x".repeat(121))
            null
        } catch (e: IllegalArgumentException) { e }
        assertTrue(thrown != null, "超过 MAX_SUMMARY 的摘要必须被拒绝")
    }
}

/** QH-P04-07：动作仲裁——停止优先、互斥、敏感排除、优先级去重。 */
class ActionArbiterTest {

    private val close = ProtectionReducer.CandidateAction.CloseAd(100)
    private val mask = ProtectionReducer.CandidateAction.MaskRegion(100)
    private val sensor = ProtectionReducer.CandidateAction.StartSensorSession(100)
    private val record = ProtectionReducer.CandidateAction.RecordOnly(100, EventType.AD_WINDOW_DETECTED, ProtectionOutcome.REQUESTED)

    private fun ctx(paused: Boolean = false, stop: Boolean = false, sensitive: Boolean = false, sensorGate: Boolean = true) =
        ActionArbiter.ExecutionContext(paused, stop, sensitive, sensorGate)

    @Test
    fun `安全停止丢弃一切新动作`() {
        assertTrue(ActionArbiter.arbitrate(listOf(close, mask, record), ctx(stop = true)).isEmpty())
    }

    @Test
    fun `暂停后不继续点击，仅保留纯记录`() {
        val out = ActionArbiter.arbitrate(listOf(close, mask, sensor, record), ctx(paused = true))
        assertTrue(out.all { it is ProtectionReducer.CandidateAction.RecordOnly }, "暂停后只允许记录")
    }

    @Test
    fun `关闭与遮罩互斥防残盾`() {
        val out = ActionArbiter.arbitrate(listOf(close, mask), ctx())
        assertTrue(out.any { it is ProtectionReducer.CandidateAction.CloseAd })
        assertFalse(out.any { it is ProtectionReducer.CandidateAction.MaskRegion })
    }

    @Test
    fun `敏感应用不产生主动动作`() {
        val out = ActionArbiter.arbitrate(listOf(close, mask, record), ctx(sensitive = true))
        assertTrue(out.all { it is ProtectionReducer.CandidateAction.RecordOnly })
    }

    @Test
    fun `Sensor 门未开不启动会话`() {
        val out = ActionArbiter.arbitrate(listOf(sensor), ctx(sensorGate = false))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `输出按优先级稳定排序`() {
        val out = ActionArbiter.arbitrate(listOf(sensor, record, close), ctx())
        assertEquals(ProtectionReducer.CandidateAction.CloseAd::class, out.first()::class)
    }
}

/** QH-P04-11：有界重试/退避/熔断/半开恢复。 */
class FailurePolicyTest {

    private class FakeClock(var now: Long = 0L) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    @Test
    fun `指数退避且封顶`() {
        val policy = FailurePolicy(FakeClock(), baseDelayMs = 1000, maxDelayMs = 8000, circuitThreshold = 10)
        var state = FailurePolicy.CircuitState()
        val delays = mutableListOf<Long>()
        repeat(5) { state = policy.onAttemptFailed(state, false); delays.add(state.lastDelayMs) }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 8000L), delays)
    }

    @Test
    fun `连续失败进入熔断`() {
        val policy = FailurePolicy(FakeClock(), circuitThreshold = 3, cooldownMs = 60_000)
        var state = FailurePolicy.CircuitState()
        repeat(3) { state = policy.onAttemptFailed(state, false) }
        assertTrue(state.opened)
        assertFalse(policy.canAttempt(state, nowMs = state.circuitOpenSinceMs + 30_000))
        assertTrue(policy.canAttempt(state, nowMs = state.circuitOpenSinceMs + 60_000), "冷却期满半开")
    }

    @Test
    fun `权限失效不重试直接熔断`() {
        val policy = FailurePolicy(FakeClock())
        val state = policy.onAttemptFailed(FailurePolicy.CircuitState(), isPermissionError = true)
        assertTrue(state.opened)
        assertEquals(0L, state.lastDelayMs, "权限类失败无退避重试")
    }

    @Test
    fun `半开探测失败重新熔断，成功完全复位`() {
        val policy = FailurePolicy(FakeClock(), circuitThreshold = 1)
        var state = policy.onAttemptFailed(FailurePolicy.CircuitState(), false)
        assertTrue(state.opened)
        state = policy.onProbeResult(state, success = false)
        assertTrue(state.isRecovering)
        state = policy.onProbeResult(state, success = true)
        assertEquals(FailurePolicy.CircuitState(), state, "成功复位不留失败痕迹")
    }
}
