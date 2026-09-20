package top.hnwen17.guard.core

import top.hnwen17.guard.core.policy.PolicyResolver
import top.hnwen17.guard.core.TriPolicy
import top.hnwen17.guard.core.session.MonotonicClock
import top.hnwen17.guard.core.session.WindowEpochTracker
import top.hnwen17.guard.core.session.WindowSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** QH-P04-03：三态策略、安全排除、临时允许、迁移回放。 */
class PolicyResolverTest {

    private val all = Capability.entries.toSet()

    @Test
    fun `INHERIT 跟随全局，OFF 压过全局 ON`() {
        assertTrue(PolicyResolver.resolve(false, all, "app").cleaner)
        val off = mapOf(Capability.CLEANER to TriPolicy.OFF)
        assertFalse(PolicyResolver.resolve(false, all, "app", appTri = off).cleaner)
        assertTrue(PolicyResolver.resolve(false, all, "app", appTri = off).touch)
    }

    @Test
    fun `全局暂停时一切为假`() {
        val resolved = PolicyResolver.resolve(globalPaused = true, globalEnabled = all, appId = "app")
        assertFalse(resolved.cleaner); assertFalse(resolved.touch); assertFalse(resolved.sensor); assertFalse(resolved.jump)
    }

    @Test
    fun `敏感应用安全排除优先于临时允许`() {
        val resolved = PolicyResolver.resolve(false, all, "bank", sensitive = true,
            temporaryAllow = setOf(Capability.CLEANER))
        assertFalse(resolved.cleaner, "安全排除不能被临时允许打开")
    }

    @Test
    fun `临时允许可以覆盖 OFF 但仅限非敏感`() {
        val off = mapOf(Capability.SENSOR to TriPolicy.OFF)
        val resolved = PolicyResolver.resolve(false, all, "app", appTri = off,
            temporaryAllow = setOf(Capability.SENSOR))
        assertTrue(resolved.sensor)
    }

    @Test
    fun `旧集合迁移回放一致且无意图丢失`() {
        val legacy = mapOf("app1" to AppPolicy(enabled = setOf(Capability.CLEANER, Capability.TOUCH)))
        val migrated = PolicyResolver.migrateLegacy(legacy)
        assertEquals(TriPolicy.ON, migrated["app1"]!![Capability.CLEANER])
        assertEquals(TriPolicy.ON, migrated["app1"]!![Capability.TOUCH])
        assertEquals(TriPolicy.OFF, migrated["app1"]!![Capability.SENSOR])
        // 回放确定性：两次迁移输出一致
        assertEquals(migrated, PolicyResolver.migrateLegacy(legacy))
    }
}

/** QH-P04-04：会话身份、窗口切换、时钟回拨免疫。 */
class WindowEpochTest {

    private class FakeClock(var now: Long = 0L) : MonotonicClock {
        override fun nowMs(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun `首次会话产生 NEW，窗口切换产生 SWITCHED 且纪元更新`() {
        val clock = FakeClock(100)
        val tracker = WindowEpochTracker(clock)
        val s1 = WindowSession(0, "com.a", 5, epoch = clock.nowMs())
        assertEquals(WindowEpochTracker.EpochChange.NEW::class, tracker.onWindowEvent(s1)::class)
        clock.advance(10)
        val s2 = WindowSession(0, "com.a", 7, epoch = clock.nowMs())
        val change = tracker.onWindowEvent(s2)
        assertTrue(change is WindowEpochTracker.EpochChange.SWITCHED)
        assertNotEquals(s1.epoch, s2.epoch)
    }

    @Test
    fun `同窗口重复事件保持纪元不变`() {
        val clock = FakeClock(100)
        val tracker = WindowEpochTracker(clock)
        val s = WindowSession(0, "com.a", 5, epoch = 100)
        tracker.onWindowEvent(s)
        clock.advance(5)
        val change = tracker.onWindowEvent(s.copy(epoch = 100))
        assertEquals(WindowEpochTracker.EpochChange.UNCHANGED::class, change::class)
        assertEquals(100L, tracker.currentEpoch())
    }

    @Test
    fun `旧会话结果自动失效`() {
        val clock = FakeClock(0)
        val tracker = WindowEpochTracker(clock)
        val s1 = WindowSession(0, "com.a", 1, epoch = 0)
        tracker.onWindowEvent(s1)
        assertTrue(tracker.isCurrent(s1))
        val s2 = WindowSession(0, "com.a", 2, epoch = 10)
        tracker.onWindowEvent(s2)
        assertFalse(tracker.isCurrent(s1), "窗口切换后旧结果必须失效")
    }

    @Test
    fun `纪元只来自单调时钟源，与墙钟无关`() {
        val monotonic = FakeClock(1000)
        val e1 = WindowEpochTracker.newEpoch(WindowSession(0, "a", 1, epoch = 0), monotonic)
        monotonic.advance(50)
        val e2 = WindowEpochTracker.newEpoch(WindowSession(0, "a", 1, epoch = 0), monotonic)
        assertTrue(e2.epoch > e1.epoch, "单调纪元必须只增不减")
        // 判定不读任何墙钟：tracker 只持有 MonotonicClock，isCurrent/currentEpoch 均无时间比较
        val tracker = WindowEpochTracker(monotonic)
        tracker.onWindowEvent(e2)
        assertTrue(tracker.isCurrent(e2))
    }
}
