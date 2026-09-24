package top.hnwen17.guard.platform.jump

import org.junit.Assert.assertEquals
import org.junit.Test
import top.hnwen17.guard.core.session.MonotonicClock

/** 跳转拦截来源识别单测（2026-09-22 来源识别版重构）。 */
class JumpInterceptorTest {

    private class FakeClock(var now: Long = 10_000L) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    @Test
    fun `广告来源跳转且已开启-拦截`() {
        val j = JumpInterceptor(FakeClock())
        assertEquals(
            JumpInterceptor.Action.BLOCK_BACK,
            j.decide("com.source", "com.ad", jumpEnabled = true, targetSensitive = false, adOrigin = true).action
        )
    }

    @Test
    fun `广告来源但未开启-只观测`() {
        val j = JumpInterceptor(FakeClock())
        assertEquals(
            JumpInterceptor.Action.OBSERVE,
            j.decide("com.source", "com.ad", jumpEnabled = false, targetSensitive = false, adOrigin = true).action
        )
    }

    @Test
    fun `无广告证据的跨包操作-放行（分享文件与正常弹窗场景）`() {
        val j = JumpInterceptor(FakeClock())
        assertEquals(
            JumpInterceptor.Action.ALLOW_NORMAL,
            j.decide("com.files", "com.tencent.mm", jumpEnabled = true, targetSensitive = false, adOrigin = false).action
        )
    }

    @Test
    fun `敏感目标一律放行且优先于来源识别`() {
        val j = JumpInterceptor(FakeClock())
        assertEquals(
            JumpInterceptor.Action.ALLOW_SENSITIVE,
            j.decide("com.source", "com.bank", jumpEnabled = true, targetSensitive = true, adOrigin = true).action
        )
    }

    @Test
    fun `同包不判定`() {
        val j = JumpInterceptor(FakeClock())
        assertEquals(
            JumpInterceptor.Action.OBSERVE,
            j.decide("com.source", "com.source", jumpEnabled = true, targetSensitive = false, adOrigin = true).action
        )
    }
}