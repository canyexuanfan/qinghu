package top.hnwen17.guard.platform.jump

import org.junit.Assert.assertEquals
import org.junit.Test
import top.hnwen17.guard.core.session.MonotonicClock

/** QH-P12/P13：跳转判定纯逻辑单测。 */
class JumpInterceptorTest {

    private class FakeClock(var now: Long = 10_000L) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    @Test
    fun `驻留足够长且显式开启-拦截`() {
        val clock = FakeClock()
        val j = JumpInterceptor(clock)
        j.onForeground("com.source", clock.nowMs())
        clock.now += 2000 // 驻留 2 秒
        assertEquals(
            JumpInterceptor.Action.BLOCK_BACK,
            j.decide("com.source", "com.ad", jumpEnabled = true, targetSensitive = false).action
        )
    }

    @Test
    fun `未开启Jump只观测`() {
        val clock = FakeClock()
        val j = JumpInterceptor(clock)
        j.onForeground("com.source", clock.nowMs())
        clock.now += 2000
        assertEquals(
            JumpInterceptor.Action.OBSERVE,
            j.decide("com.source", "com.ad", jumpEnabled = false, targetSensitive = false).action
        )
    }

    @Test
    fun `敏感目标一律放行且优先于拦截`() {
        val clock = FakeClock()
        val j = JumpInterceptor(clock)
        j.onForeground("com.source", clock.nowMs())
        clock.now += 2000
        assertEquals(
            JumpInterceptor.Action.ALLOW_SENSITIVE,
            j.decide("com.source", "com.bank", jumpEnabled = true, targetSensitive = true).action
        )
    }

    @Test
    fun `驻留过短视为用户主动路径放行`() {
        val clock = FakeClock()
        val j = JumpInterceptor(clock, minForegroundMs = 800)
        j.onForeground("com.source", clock.nowMs())
        clock.now += 100 // 驻留 100ms：用户快速切换
        assertEquals(
            JumpInterceptor.Action.ALLOW_USER_PATH,
            j.decide("com.source", "com.ad", jumpEnabled = true, targetSensitive = false).action
        )
    }

    @Test
    fun `同包不判定`() {
        val clock = FakeClock()
        val j = JumpInterceptor(clock)
        assertEquals(
            JumpInterceptor.Action.OBSERVE,
            j.decide("com.a", "com.a", jumpEnabled = true, targetSensitive = false).action
        )
    }
}
