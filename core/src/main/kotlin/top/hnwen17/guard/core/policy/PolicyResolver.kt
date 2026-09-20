package top.hnwen17.guard.core.policy

import top.hnwen17.guard.core.AppPolicy
import top.hnwen17.guard.core.Capability
import top.hnwen17.guard.core.TriPolicy

/**
 * 三态应用策略（QH-P04-03）。
 *
 * INHERIT 跟随全局；ON/OFF 为应用级覆盖。解析优先级：
 *   安全排除（sensitive，永远拒绝） > 临时允许（temporaryAllow，有界到期） > 应用覆盖（ON/OFF） > 全局。
 *
 * 旧配置（AppPolicy.enabled 集合）迁移：集合含该能力 → ON，否则 → OFF；迁移不丢用户意图。
 * 三态覆盖表由数据层（SettingsCodec schema 2）持久化；TriPolicy 单一定义在 core.Models。
 */
data class ResolvedPolicy(
    val cleaner: Boolean,
    val touch: Boolean,
    val sensor: Boolean,
    val jump: Boolean
) {
    operator fun get(capability: Capability): Boolean = when (capability) {
        Capability.CLEANER -> cleaner
        Capability.TOUCH -> touch
        Capability.SENSOR -> sensor
        Capability.JUMP -> jump
    }
}

object PolicyResolver {

    /** TriPolicy 判定：INHERIT 跟随全局，其余为显式覆盖。 */
    fun isOn(tri: TriPolicy, globalEnabled: Boolean): Boolean = when (tri) {
        TriPolicy.ON -> true
        TriPolicy.OFF -> false
        TriPolicy.INHERIT -> globalEnabled
    }

    /** 旧 enabled 集合 → 三态（迁移纯函数）。 */
    fun fromLegacy(inSet: Boolean): TriPolicy = if (inSet) TriPolicy.ON else TriPolicy.OFF

    /**
     * 解析某应用的能力开关。
     *
     * @param globalPaused   全局暂停：一切为假（安全优先）。
     * @param globalEnabled  全局能力开关集合（旧语义）。
     * @param appLegacy      旧应用策略；null 视为无应用配置（全 INHERIT）。
     * @param appTri         新三态覆盖表；同能力同时给出时优先于旧集合推导。
     * @param sensitive      敏感应用（支付/登录/银行）：安全排除，全部拒绝，优先级最高。
     * @param temporaryAllow 临时允许窗口（用户点了"本次允许"且未到期）：可覆盖 OFF，
     *                       但不能打开安全排除的应用。
     */
    fun resolve(
        globalPaused: Boolean,
        globalEnabled: Set<Capability>,
        appId: String,
        appLegacy: AppPolicy? = null,
        appTri: Map<Capability, TriPolicy> = emptyMap(),
        sensitive: Boolean = false,
        temporaryAllow: Set<Capability> = emptySet()
    ): ResolvedPolicy {
        if (sensitive) return ResolvedPolicy(cleaner = false, touch = false, sensor = false, jump = false)
        if (globalPaused) return ResolvedPolicy(cleaner = false, touch = false, sensor = false, jump = false)
        fun enabled(cap: Capability): Boolean {
            if (cap in temporaryAllow) return true
            // 无任何应用级配置 = INHERIT（跟随全局）；有旧集合才映射 ON/OFF
            val tri = appTri[cap]
                ?: (appLegacy?.let { fromLegacy(cap in it.enabled) } ?: TriPolicy.INHERIT)
            return isOn(tri, cap in globalEnabled)
        }
        return ResolvedPolicy(
            cleaner = enabled(Capability.CLEANER),
            touch = enabled(Capability.TOUCH),
            sensor = enabled(Capability.SENSOR),
            jump = enabled(Capability.JUMP)
        )
    }

    /**
     * 旧配置回放迁移：把旧 AppPolicy（enabled 集合）无损映射为三态表。
     * 纯函数：相同输入永远得到相同输出，便于回放测试。
     */
    fun migrateLegacy(policies: Map<String, AppPolicy>): Map<String, Map<Capability, TriPolicy>> =
        policies.mapValues { (_, policy) ->
            Capability.entries.associateWith { cap -> fromLegacy(cap in policy.enabled) }
        }
}
