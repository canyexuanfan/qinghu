package top.hnwen17.guard.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import top.hnwen17.guard.core.*
import java.time.LocalDate
import java.time.ZoneId

/** Compiled only into preview. Aggregate counts are fixtures, never reported as real interceptions. */
object SourceFactory { fun create(): FrontendSource = PreviewSource() }
private class PreviewSource : FrontendSource {
    override val preview = true
    private val normal = Capability.entries.associateWith { Availability.ACTIVE }
    override val availability = MutableStateFlow(normal)
    private val catalog = listOf(
        AppEntry("sample.shop", "某购物 App", "bag", true),
        AppEntry("sample.shortvideo", "某短视频 App", "shortvideo", true),
        AppEntry("sample.news", "某资讯 App", "news", true),
        AppEntry("sample.social", "社交 App", "chat"),
        AppEntry("sample.pay", "支付 App", "wallet"),
        AppEntry("sample.video", "视频 App", "play"),
        AppEntry("sample.music", "音乐 App", "music"),
        AppEntry("sample.map", "地图 App", "map"),
        AppEntry("sample.mail", "邮箱 App", "mail"),
        AppEntry("sample.browser", "浏览器 App", "globe"),
        AppEntry("sample.book", "阅读 App", "book"),
        AppEntry("sample.tool", "工具 App", "sliders"),
        AppEntry("sample.system.settings", "系统设置", "sliders", system = true),
        AppEntry("sample.system.browser", "系统浏览器", "globe", system = true)
    )
    override val defaultSettings = Settings(policies = mapOf(
        "sample.shortvideo" to AppPolicy(setOf(Capability.CLEANER, Capability.SENSOR)),
        "sample.news" to AppPolicy(setOf(Capability.CLEANER, Capability.TOUCH)),
        "sample.video" to AppPolicy(setOf(Capability.CLEANER, Capability.SENSOR, Capability.TOUCH)),
        "sample.social" to AppPolicy(setOf(Capability.CLEANER, Capability.TOUCH)),
        "sample.pay" to AppPolicy(setOf(Capability.CLEANER, Capability.TOUCH)),
        "sample.music" to AppPolicy(emptySet()), "sample.map" to AppPolicy(emptySet()),
        "sample.mail" to AppPolicy(emptySet()), "sample.tool" to AppPolicy(emptySet()),
        "sample.browser" to AppPolicy(setOf(Capability.CLEANER, Capability.TOUCH)),
        "sample.book" to AppPolicy(setOf(Capability.CLEANER, Capability.TOUCH)),
        "sample.system.settings" to AppPolicy(emptySet()), "sample.system.browser" to AppPolicy(emptySet())
    ))
    override val summaryToday = Summary(27, 12, 7, 8)
    override val appRecordCounts = mapOf("sample.shop" to 23)
    private fun event(id: Long, day: Long, hour: Int, minute: Int, appIndex: Int, cap: Capability): ProtectionRecord {
        val app = catalog[appIndex]
        return ProtectionRecord(id, app.id, app.name, cap,
            LocalDate.now().minusDays(day).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            when(cap) {
                Capability.CLEANER -> Outcome.CLOSED; Capability.TOUCH -> Outcome.TOUCH_BLOCKED
                Capability.SENSOR -> Outcome.SENSOR_APPLIED; Capability.JUMP -> Outcome.START_REJECTED
            }, when(cap) {
                Capability.CLEANER -> "开屏广告已自动跳过。"
                Capability.TOUCH -> "已拦截一次广告区域点击。"
                Capability.SENSOR -> "尝试通过手机晃动触发广告跳转，已阻止。"
                Capability.JUMP -> "尝试打开其他应用，已阻止。"
            }, sample = true)
    }
    override val records = listOf(
        event(1, 0, 12, 43, 0, Capability.SENSOR), event(2, 0, 12, 31, 2, Capability.CLEANER),
        event(3, 0, 11, 56, 0, Capability.TOUCH), event(4, 0, 10, 15, 1, Capability.JUMP),
        event(5, 0, 9, 32, 2, Capability.CLEANER), event(6, 1, 22, 18, 0, Capability.TOUCH),
        event(7, 1, 20, 41, 0, Capability.SENSOR), event(8, 1, 18, 3, 2, Capability.CLEANER),
        event(9, 1, 15, 27, 1, Capability.JUMP)
    )
    override suspend fun loadApplications(context: Context) = catalog
    override fun previewScenario(scenario: Int) {
        availability.value = when(scenario) {
            1 -> normal.mapValues { if(it.key == Capability.SENSOR || it.key == Capability.JUMP) Availability.NEEDS_PERMISSION else it.value }
            2 -> Capability.entries.associateWith { Availability.UNCONNECTED }
            3 -> Capability.entries.associateWith { Availability.ERROR }
            4 -> normal + (Capability.JUMP to Availability.LIMITED)
            else -> normal
        }
    }
}
