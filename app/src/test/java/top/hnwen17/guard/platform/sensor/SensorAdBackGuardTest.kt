package top.hnwen17.guard.platform.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.hnwen17.guard.core.session.MonotonicClock

/** QH-P10 实用路径：广告 SDK Activity 识别与冷却。 */
class SensorAdBackGuardTest {

    private class FakeClock(var now: Long = 0L) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    @Test
    fun `穿山甲开屏Activity命中`() {
        val guard = SensorAdBackGuard(FakeClock())
        assertTrue(guard.shouldBack("com.bytedance.sdk.openadsdk.core.component.splash.SplashADActivity", 0L))
    }

    @Test
    fun `普通Activity不命中`() {
        val guard = SensorAdBackGuard(FakeClock())
        assertFalse(guard.shouldBack("com.example.app.MainActivity", 0L))
        assertFalse(guard.shouldBack(null, 0L))
    }

    @Test
    fun `冷却期内不重复`() {
        val guard = SensorAdBackGuard(FakeClock(), cooldownMs = 3000)
        assertTrue(guard.shouldBack("com.x.openadsdk.AdActivity", 0L))
        assertFalse("冷却期内", guard.shouldBack("com.x.openadsdk.AdActivity", 1000L))
        assertTrue("冷却后允许", guard.shouldBack("com.x.openadsdk.AdActivity", 4000L))
    }
}
