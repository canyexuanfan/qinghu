package top.hnwen17.guard.platform.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.hnwen17.guard.core.capability.SafetyGate
import top.hnwen17.guard.core.session.MonotonicClock

/** QH-P10：Sensor 安全门与抑制会话决策单测。 */
class SensorGateTest {

    private class FakeClock(var now: Long = 0L) : MonotonicClock {
        override fun nowMs(): Long = now
    }

    @Test
    fun `单次峰值不武装-窗口内两次峰值武装会话`() {
        val clock = FakeClock()
        val gate = SensorGate(clock)
        var state = SensorGate.State()
        var result: Pair<SensorGate.State, Boolean>
        result = gate.onAccelerometer(state, 15f, nowMs = 100)
        state = result.first; var armed = result.second
        assertFalse("单次峰值不武装", armed)
        result = gate.onAccelerometer(state, 15f, nowMs = 500)
        state = result.first; armed = result.second
        assertTrue("窗口内第二次峰值武装会话", armed)
        // 会话期内持续抑制
        result = gate.onAccelerometer(state, 1f, nowMs = 1000)
        state = result.first; armed = result.second
        assertTrue("会话期内抑制", armed)
        // 会话过期后不再抑制
        result = gate.onAccelerometer(state, 1f, nowMs = 5000)
        state = result.first; armed = result.second
        assertFalse("会话过期不再抑制", armed)
    }

    @Test
    fun `无传感器设备-安全门NO_GO`() {
        val gate = SensorGate(FakeClock())
        assertEquals(SafetyGate.NO_GO, gate.evaluateGate(hasSystemSuppressionProof = false, sensorsAvailable = false))
    }

    @Test
    fun `系统能力未证明-即使有传感器也NO_GO`() {
        val gate = SensorGate(FakeClock())
        assertEquals(SafetyGate.NO_GO, gate.evaluateGate(hasSystemSuppressionProof = false, sensorsAvailable = true))
    }

    @Test
    fun `系统能力证明后GO`() {
        val gate = SensorGate(FakeClock())
        assertEquals(SafetyGate.GO, gate.evaluateGate(hasSystemSuppressionProof = true, sensorsAvailable = true))
    }
}
