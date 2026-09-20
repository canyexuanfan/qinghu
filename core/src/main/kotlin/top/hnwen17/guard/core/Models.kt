package top.hnwen17.guard.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class Capability(val title: String, val description: String) {
    CLEANER("广告净化", "自动关闭 / 跳过\n可安全识别的广告"),
    TOUCH("防止广告误触", "保护高风险广告区域\n避免意外点击"),
    SENSOR("防摇一摇广告", "阻止广告利用手机动作触发"),
    JUMP("异常跳转保护", "阻止非主动的应用跳转")
}
enum class Availability { ACTIVE, LIMITED, UNCONNECTED, NEEDS_PERMISSION, DISABLED, ERROR }
enum class Strength(val label: String) { GENTLE("轻度保护"), SMART("智能保护"), STRICT("严格保护") }
enum class Outcome(val label: String) { CLOSED("已关闭"), TOUCH_BLOCKED("已保护"), SENSOR_APPLIED("已执行"), START_REJECTED("已阻止"), OBSERVED("拦截失败") }
enum class AppSort { NAME, ENABLED_FIRST }

/** 三态应用策略：INHERIT 跟随全局；ON/OFF 应用级覆盖（数据层持久化，解析见 core.policy）。 */
enum class TriPolicy { INHERIT, ON, OFF }

data class AppEntry(val id: String, val name: String, val icon: String, val recommended: Boolean = false, val system: Boolean = false)
data class AppPolicy(val enabled: Set<Capability> = Capability.entries.toSet(), val strength: Strength = Strength.SMART,
                     val tri: Map<Capability, TriPolicy> = emptyMap())
data class Settings(
    val paused: Boolean = false,
    val enabled: Set<Capability> = Capability.entries.toSet(),
    val autoUpdate: Boolean = true,
    val reduceMotion: Boolean = true,
    val policies: Map<String, AppPolicy> = emptyMap(),
    /** 临时允许：能力 → 到期 epoch ms（有界例外，到期即失效；安全排除不受其影响）。 */
    val temporaryAllow: Map<Capability, Long> = emptyMap()
)
data class ProtectionRecord(
    val id: Long, val appId: String, val appName: String, val capability: Capability,
    val timestamp: Long, val outcome: Outcome, val detail: String, val sample: Boolean = false
)
data class AppState(
    val loading: Boolean = true,
    val preview: Boolean = false,
    val apps: List<AppEntry> = emptyList(),
    val settings: Settings = Settings(),
    val availability: Map<Capability, Availability> = Capability.entries.associateWith { Availability.UNCONNECTED },
    val records: List<ProtectionRecord> = emptyList(),
    val error: String? = null,
    val summaryOverride: Summary? = null,
    val appRecordCounts: Map<String, Int> = emptyMap()
) {
    fun policy(id: String) = settings.policies[id] ?: AppPolicy()
    fun effective(capability: Capability, appId: String? = null): Availability {
        if (settings.paused || capability !in settings.enabled ||
            (appId != null && capability !in policy(appId).enabled)) return Availability.DISABLED
        return availability[capability] ?: Availability.UNCONNECTED
    }
}
data class Summary(val total: Int, val cleaned: Int, val touches: Int, val others: Int)

fun summarize(records: List<ProtectionRecord>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Summary {
    val current = records.filter { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == today }
        .filter { it.outcome != Outcome.OBSERVED }
    return Summary(current.size, current.count { it.capability == Capability.CLEANER },
        current.count { it.capability == Capability.TOUCH }, current.count { it.capability == Capability.SENSOR || it.capability == Capability.JUMP })
}
fun filterApps(apps: List<AppEntry>, query: String, sort: AppSort, settings: Settings): List<AppEntry> {
    val q = query.trim().lowercase(Locale.ROOT)
    val result = apps.filter { it.name.lowercase(Locale.ROOT).contains(q) || it.id.lowercase(Locale.ROOT).contains(q) }
    return when (sort) {
        AppSort.NAME -> result.sortedBy { it.name }
        AppSort.ENABLED_FIRST -> result.sortedWith(compareByDescending<AppEntry> {
            (settings.policies[it.id] ?: AppPolicy()).enabled.isNotEmpty()
        }.thenBy { it.name })
    }
}
fun filterRecords(records: List<ProtectionRecord>, capability: Capability?, appId: String?, todayOnly: Boolean,
                  today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<ProtectionRecord> = records.asSequence()
    .filter { capability == null || it.capability == capability }
    .filter { appId == null || it.appId == appId }
    .filter { !todayOnly || Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == today }
    .sortedByDescending { it.timestamp }.take(120).toList()
fun toggle(source: Set<Capability>, item: Capability, enabled: Boolean): Set<Capability> =
    if (enabled) source + item else source - item
fun statusLabel(status: Availability): String = when (status) {
    Availability.ACTIVE -> "已开启"
    Availability.LIMITED -> "部分保护"
    Availability.UNCONNECTED -> "待接入"
    Availability.NEEDS_PERMISSION -> "待授权"
    Availability.DISABLED -> "未开启"
    Availability.ERROR -> "异常"
}
