package top.hnwen17.guard.data

import top.hnwen17.guard.core.records.EventType
import top.hnwen17.guard.core.records.ProtectionOutcome
import top.hnwen17.guard.data.records.RecordStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** QH-P08-07 记录口径：一次动作只计一条，终态替换同会话先前记录。 */
class RecordStoreTest {
    private fun store() = RecordStore()
    private val et = EventType.AD_CLOSE_CLICKED
    private val vt = EventType.AD_WINDOW_CLOSED_VERIFIED

    @Test
    fun `verified replaces executed in same session`() {
        val s = store()
        s.record("r", 1, "pkg", 100, ProtectionOutcome.EXECUTED, 1_000L, et)
        s.record("r", 1, "pkg", 100, ProtectionOutcome.VERIFIED, 2_000L, vt)
        val all = s.all.value
        assertEquals(1, all.size)
        assertEquals(ProtectionOutcome.VERIFIED, all[0].outcome)
        assertEquals(2_000L, all[0].atEpochMs)
    }

    @Test
    fun `duplicate verified in same session ignored`() {
        val s = store()
        s.record("r", 1, "pkg", 100, ProtectionOutcome.VERIFIED, 1_000L, vt)
        s.record("r", 1, "pkg", 100, ProtectionOutcome.VERIFIED, 2_000L, vt)
        assertEquals(1, s.all.value.size)
    }

    @Test
    fun `different sessions both recorded`() {
        val s = store()
        s.record("r", 1, "pkg", 100, ProtectionOutcome.VERIFIED, 1_000L, vt)
        s.record("r", 1, "pkg", 200, ProtectionOutcome.VERIFIED, 2_000L, vt)
        assertEquals(2, s.all.value.size)
    }

    @Test
    fun `late executed after verified dropped`() {
        val s = store()
        s.record("r", 1, "pkg", 100, ProtectionOutcome.VERIFIED, 1_000L, vt)
        s.record("r", 1, "pkg", 100, ProtectionOutcome.EXECUTED, 2_000L, et)
        assertEquals(1, s.all.value.size)
        assertEquals(ProtectionOutcome.VERIFIED, s.all.value[0].outcome)
    }

    @Test
    fun `observed and sensor sessions never counted`() {
        val s = store()
        s.record("r", 1, "pkg", 100, ProtectionOutcome.REQUESTED, 1_000L, vt)
        s.record("r", 1, "pkg", 100, ProtectionOutcome.VERIFIED, 1_000L, EventType.SENSOR_SESSION_STARTED)
        assertEquals(0, s.all.value.size)
    }

    @Test
    fun `failed replaces executed`() {
        val s = store()
        s.record("r", 1, "pkg", 100, ProtectionOutcome.EXECUTED, 1_000L, et)
        s.record("r", 1, "pkg", 100, ProtectionOutcome.FAILED, 2_000L, EventType.AD_CLOSE_FAILED)
        val all = s.all.value
        assertEquals(1, all.size)
        assertEquals(ProtectionOutcome.FAILED, all[0].outcome)
    }
}
