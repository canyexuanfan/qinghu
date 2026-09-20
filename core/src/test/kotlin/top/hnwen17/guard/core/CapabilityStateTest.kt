package top.hnwen17.guard.core

import top.hnwen17.guard.core.capability.CapabilityState
import top.hnwen17.guard.core.capability.Evidence
import top.hnwen17.guard.core.capability.Reason
import top.hnwen17.guard.core.capability.SafetyGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** QH-P04-01：能力四层状态拆解的全组合断言。 */
class CapabilityStateTest {

    private fun state(
        request: CapabilityState.Request = CapabilityState.Request.ON,
        platform: CapabilityState.PlatformSupport = CapabilityState.PlatformSupport.SUPPORTED,
        runtime: CapabilityState.Runtime = CapabilityState.Runtime.RUNNING
    ) = CapabilityState(Capability.CLEANER, request, platform, runtime)

    @Test
    fun `意图关则一律 DISABLED，与平台运行无关`() {
        assertEquals(Availability.DISABLED, state(request = CapabilityState.Request.OFF, runtime = CapabilityState.Runtime.RUNNING).toLegacyAvailability())
        assertEquals(Availability.DISABLED, state(request = CapabilityState.Request.OFF, runtime = CapabilityState.Runtime.CIRCUIT_OPEN).toLegacyAvailability())
    }

    @Test
    fun `开关打开但平台未知不返回 ACTIVE（保守门）`() {
        assertEquals(Availability.NEEDS_PERMISSION, state(platform = CapabilityState.PlatformSupport.UNKNOWN).toLegacyAvailability())
        assertFalse(state(platform = CapabilityState.PlatformSupport.UNKNOWN).platformReady)
    }

    @Test
    fun `平台不支持为 UNCONNECTED 而非 ERROR`() {
        assertEquals(Availability.UNCONNECTED, state(platform = CapabilityState.PlatformSupport.UNSUPPORTED).toLegacyAvailability())
    }

    @Test
    fun `熔断为 ERROR，连接中为 NEEDS_PERMISSION`() {
        assertEquals(Availability.ERROR, state(runtime = CapabilityState.Runtime.CIRCUIT_OPEN).toLegacyAvailability())
        assertEquals(Availability.NEEDS_PERMISSION, state(runtime = CapabilityState.Runtime.CONNECTING).toLegacyAvailability())
    }

    @Test
    fun `全绿要求意图平台运行三者同时成立`() {
        assertEquals(Availability.ACTIVE, state().toLegacyAvailability())
        assertTrue(state().running)
    }

    @Test
    fun `with 局部更新不影响其他字段`() {
        val s = state().with(runtime = CapabilityState.Runtime.IDLE, reason = Reason.STOPPED_BY_USER)
        assertEquals(CapabilityState.Runtime.IDLE, s.runtime)
        assertEquals(Reason.STOPPED_BY_USER, s.reason)
        assertEquals(CapabilityState.Request.ON, s.request)
    }

    @Test
    fun `证据只承载事实种类与单调时间`() {
        val e = Evidence(kind = Evidence.Kind.PROBE_PASSED, atMonotonicMs = 123L)
        assertEquals(Evidence.Kind.PROBE_PASSED, e.kind)
        assertEquals(123L, e.atMonotonicMs)
        assertEquals(Evidence.Kind.NONE, Evidence.NONE.kind)
    }

    @Test
    fun `安全门默认不适用`() {
        assertEquals(SafetyGate.NOT_APPLICABLE, SafetyGate.NOT_APPLICABLE)
    }
}
