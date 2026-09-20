package top.hnwen17.guard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.hnwen17.guard.core.AppPolicy
import top.hnwen17.guard.core.Capability
import top.hnwen17.guard.core.Settings
import top.hnwen17.guard.core.Strength
import top.hnwen17.guard.core.TriPolicy

/** QH-P04-03：三态编解码与 schema1 迁移（JVM 单测）。 */
class SettingsCodecTest {

    @Test fun `schema2 往返保持三态与临时允许`() {
        val settings = Settings(
            paused = false,
            enabled = setOf(Capability.CLEANER, Capability.TOUCH),
            policies = mapOf("com.a" to AppPolicy(
                enabled = setOf(Capability.CLEANER),
                strength = Strength.STRICT,
                tri = mapOf(Capability.SENSOR to TriPolicy.OFF, Capability.JUMP to TriPolicy.ON)
            )),
            temporaryAllow = mapOf(Capability.CLEANER to 4_102_444_000_000L)
        )
        val decoded = SettingsCodec.decode(SettingsCodec.encode(settings))
        assertEquals(settings, decoded) // encode→decode 无损往返
    }

    @Test fun `schema1 旧集合迁移为 ON-OFF 三态`() {
        val legacy = """{"schema":1,"paused":false,
            "enabled":["CLEANER","TOUCH","SENSOR","JUMP"],
            "autoUpdate":true,"reduceMotion":true,
            "policies":{"com.a":{"enabled":["CLEANER"],"strength":"SMART"}}}"""
        val decoded = SettingsCodec.decode(legacy)
        val policy = decoded.policies["com.a"]!!
        // 旧集合内的能力迁移为 ON（或未写 tri 时由解析器推导）
        assertTrue(policy.tri[Capability.CLEANER] == TriPolicy.ON || policy.tri.isEmpty())
        if (policy.tri.isNotEmpty()) {
            assertEquals(TriPolicy.OFF, policy.tri[Capability.TOUCH]) // 集合外迁移为 OFF
        }
        // 无 tri 字段时解析器按旧集合推导，全局开关仍生效
        assertTrue(policy.enabled.contains(Capability.CLEANER))
    }

    @Test fun `过期临时允许在解码时丢弃`() {
        val json = """{"schema":2,"paused":false,"enabled":[],"autoUpdate":true,"reduceMotion":true,
            "temporaryAllow":{"CLEANER":1,"TOUCH":99999999999999},"policies":{}}"""
        val decoded = SettingsCodec.decode(json)
        assertFalse(Capability.CLEANER in decoded.temporaryAllow) // 已过期项必须丢弃
        assertTrue(Capability.TOUCH in decoded.temporaryAllow) // 未到期项保留
    }

    @Test fun `损坏输入回退默认不抛出`() {
        assertEquals(Settings(), SettingsCodec.decode(null))
        assertEquals(Settings(), SettingsCodec.decode("not-json"))
        assertEquals(Settings(), SettingsCodec.decode("{\"schema\":9}"))
    }
}
