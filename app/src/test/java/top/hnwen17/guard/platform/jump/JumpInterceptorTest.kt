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
    fun `近期用户点击则放行（用户主动路径）`() {
        val clock = FakeClock()
        val j = JumpInterceptor(clock)
        j.onForeground("com.source", clock.nowMs())
        clock.now += 1000
        j.onUserInteraction(clock.nowMs()) // 用户点了图标/按钮
        clock.now += 500 // 点击后 500ms 发生跳转
        assertEquals(
            JumpInterceptor.Action.ALLOW_USER_PATH,
            j.decide("com.source", "com.ad", jumpEnabled = true, targetSensitive = false).action
        )
    }

    @Test
    fun `驻留极短且无交互（开屏秒拉起）也拦截`() {
        val clock = FakeClock()
        val j = JumpInterceptor(clock)
        j.onForeground("com.source", clock.nowMs())
        clock.now += 300 // 驻留仅 300ms：旧逻辑放行（真机「只拦极少」的主因），新逻辑拦截
        assertEquals(
            JumpInterceptor.Action.BLOCK_BACK,
            j.decide("com.source", "com.ad", jumpEnabled = true, targetSensitive = false).action
        )
    }

    @Test
    fun `点击超过2秒后跳转不再视为用户路径`() {
        val clock = FakeClock()
        val j = JumpInterceptor(clock)
        j.onForeground("com.source", clock.nowMs())
        clock.now += 500
        j.onUserInteraction(clock.nowMs())
        clock.now += 3000 // 点击已过去 3 秒（> interactionRecentMs 2000）
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
    fun `驻留过短且无交互（开屏秒拉起）也拦截`() {
        val clock = FakeClock()
        val j = JumpInterceptor(clock)
        j.onForeground("com.source", clock.nowMs())
        clock.now += 100 // 驻留 100ms 且无用户交互：被动拉起，拦截（旧逻辑此处放行）
        assertEquals(
            JumpInterceptor.Action.BLOCK_BACK,
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
