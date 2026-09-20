package top.hnwen17.guard.data.records

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import top.hnwen17.guard.core.records.EventType
import android.content.Context
import top.hnwen17.guard.core.records.ProtectionOutcome

/**
 * 真实防护记录仓库（QH-P08-07）。
 *
 * 只接受**已证实**的执行结果：
 * - CLOSE_CONFIRMED（后验 VERIFIED）与失败（FAILED/EXEC fail）；
 * - 会话去重：同 windowEpoch + 同 ruleId 只记一条成功（防风暴重复计数）；
 * - 容量有界（[CAPACITY] 条环形），小状态更新（不可变列表整体替换）。
 *
 * 统计口径守门：REQUESTED/UNKNOWN/OBSERVE 类**不得**进入本仓库（调用方过滤）。
 */
class RecordStore {

    data class CleanerRecord(
        val ruleId: String,
        val ruleVersion: Int,
        val packageName: String,
        val windowEpoch: Long,
        val outcome: ProtectionOutcome,
        val atEpochMs: Long,
        val capability: String = "CLEANER" // QH-P18：CLEANER/TOUCH/SENSOR/JUMP
    )

    private val records = ArrayDeque<CleanerRecord>()
    private val verifiedSessions = HashSet<String>()
    private val executedSessions = HashSet<String>()

    private val _all = MutableStateFlow<List<CleanerRecord>>(emptyList())
    val all: StateFlow<List<CleanerRecord>> = _all.asStateFlow()

    @Synchronized
    fun record(
        ruleId: String, ruleVersion: Int, packageName: String,
        windowEpoch: Long, outcome: ProtectionOutcome, atEpochMs: Long, eventType: EventType,
        capabilityOverride: String? = null // QH-P18：load 恢复时保留存量分类
    ) {
        // 口径守门：只有 EXECUTED/VERIFIED/FAILED 是"执行结果"；请求与观察不计数
        if (outcome != ProtectionOutcome.EXECUTED &&
            outcome != ProtectionOutcome.VERIFIED &&
            outcome != ProtectionOutcome.FAILED &&
            outcome != ProtectionOutcome.START_REJECTED) return
        if (eventType == EventType.SENSOR_SESSION_STARTED ||
            eventType == EventType.SENSOR_SESSION_EXTENDED ||
            eventType == EventType.SENSOR_SESSION_ENDED) return // Sensor 会话≠广告阻止
        val sessionKey = "$windowEpoch:$ruleId"
        if (outcome == ProtectionOutcome.EXECUTED && !executedSessions.add(sessionKey)) return // 同会话执行去重（遮罩/重show防刷屏）
        when (outcome) {
            ProtectionOutcome.VERIFIED -> if (!verifiedSessions.add(sessionKey)) return // 同会话只证实一次
            ProtectionOutcome.EXECUTED -> if (sessionKey in verifiedSessions) return // 已证实，迟到执行事件不回退
            else -> { }
        }
        // 一次动作只计一条：终态（VERIFIED/FAILED/START_REJECTED）替换同会话先前记录
        if (outcome != ProtectionOutcome.EXECUTED)
            records.removeAll { "${it.windowEpoch}:${it.ruleId}" == sessionKey }
        if (records.size >= CAPACITY) records.removeFirst()
        val capability = capabilityOverride ?: when (eventType) {
            EventType.LAUNCH_INTENT_OBSERVED -> "JUMP"
            EventType.SENSOR_AD_BACKED -> "SENSOR"
            EventType.RISK_REGION_MASKED, EventType.MASK_DISMISSED_BY_USER, EventType.TOUCH_BLOCK_FAILED -> "TOUCH"
            else -> "CLEANER"
        }
        records.addLast(
            CleanerRecord(ruleId, ruleVersion, packageName, windowEpoch, outcome, atEpochMs, capability)
        )
        _all.value = records.toList()
    }

    fun verifiedTodayCount(nowDayMs: Long): Int = _all.value.count {
        it.outcome == ProtectionOutcome.VERIFIED && it.atEpochMs >= nowDayMs
    }

    @Synchronized
    fun clear() {
        records.clear(); verifiedSessions.clear(); executedSessions.clear(); _all.value = emptyList()
    }

    companion object {
        const val CAPACITY = 120

        /** QH-P08-07 完全态：JSON 文件持久化（应用私有外部目录，无需权限；崩溃/重启后记录不丢）。 */
        fun save(context: Context, store: RecordStore) {
            try {
                val arr = JSONArray()
                for (r in store.all.value) arr.put(JSONObject()
                    .put("ruleId", r.ruleId).put("ruleVersion", r.ruleVersion)
                    .put("packageName", r.packageName).put("windowEpoch", r.windowEpoch)
                    .put("outcome", r.outcome.name).put("atEpochMs", r.atEpochMs).put("capability", r.capability))
                val dir = context.getExternalFilesDir(null) ?: return
                java.io.File(dir, "protection_records.json").writeText(arr.toString())
            } catch (_: Exception) { }
        }

        fun load(context: Context, store: RecordStore) {
            try {
                val dir = context.getExternalFilesDir(null) ?: return
                val f = java.io.File(dir, "protection_records.json")
                if (!f.exists()) return
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    store.record(o.getString("ruleId"), o.getInt("ruleVersion"), o.getString("packageName"),
                        o.getLong("windowEpoch"),
                        runCatching { ProtectionOutcome.valueOf(o.getString("outcome")) }.getOrDefault(ProtectionOutcome.EXECUTED),
                        o.getLong("atEpochMs"), EventType.AD_WINDOW_CLOSED_VERIFIED, o.optString("capability", "CLEANER"))
                }
            } catch (_: Exception) { }
        }
    }
}
