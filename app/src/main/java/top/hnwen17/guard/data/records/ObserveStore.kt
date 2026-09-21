package top.hnwen17.guard.data.records

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import android.content.Context

/**
 * QH-P18 观察日志（用户诉求：出现广告就必须有痕迹——成功/失败/待适配）。
 *
 * 记录引擎「看到疑似广告窗口但没有规则执行」的场景，供：
 * 1. 记录页展示「识别到广告窗口」条目（用户可见引擎活着、看到了什么）；
 * 2. 用户导出反馈 → 按样本补精准规则（P18 闭环）。
 *
 * 口径：本店绝不记录已成功执行的广告（那在 [RecordStore]）；样本 ≤5 条、每条 ≤48 字符，
 * 仅存本机应用私有目录，不上传；敏感窗口（支付/密码）在引擎主链路已排除，不会到达这里。
 */
class ObserveStore {

    data class Observation(
        val packageName: String,
        val className: String,
        val reason: String, // miss=无规则命中 / exec_fail=有规则但点击失败
        val atEpochMs: Long,
        val samples: List<String>
    )

    private val observations = ArrayDeque<Observation>()
    private val seenRecently = HashMap<String, Long>() // dedupKey → lastMs

    private val _all = MutableStateFlow<List<Observation>>(emptyList())
    val all: StateFlow<List<Observation>> = _all.asStateFlow()

    @Synchronized
    fun observe(
        packageName: String, className: String, reason: String,
        atEpochMs: Long, samples: List<String>
    ): Boolean {
        val key = "$packageName|$reason|" + className.take(96) // 类名参与去重：不同广告窗体各自留痕（连续测试不再被吞）
        val last = seenRecently[key]
        if (last != null && atEpochMs - last < DEDUP_WINDOW_MS) return false
        seenRecently[key] = atEpochMs
        if (seenRecently.size > 128) {
            val cutoff = atEpochMs - DEDUP_WINDOW_MS
            seenRecently.entries.removeIf { it.value < cutoff }
        }
        if (observations.size >= CAPACITY) observations.removeFirst()
        observations.addLast(
            Observation(packageName, className.take(96), reason, atEpochMs, samples.take(5).map { it.take(48) })
        )
        _all.value = observations.toList()
        return true
    }

    @Synchronized
    fun clear() {
        observations.clear(); seenRecently.clear(); _all.value = emptyList()
    }

    companion object {
        const val CAPACITY = 60
        const val DEDUP_WINDOW_MS = 10 * 60_000L // 同窗口线索 10 分钟去重，防风暴

        fun save(context: Context, store: ObserveStore) {
            try {
                val arr = JSONArray()
                for (o in store.all.value) arr.put(JSONObject()
                    .put("packageName", o.packageName).put("className", o.className)
                    .put("reason", o.reason).put("atEpochMs", o.atEpochMs)
                    .put("samples", JSONArray(o.samples)))
                val dir = context.getExternalFilesDir(null) ?: return
                java.io.File(dir, "ad_observations.json").writeText(arr.toString())
            } catch (_: Exception) { }
        }

        fun load(context: Context, store: ObserveStore) {
            try {
                val dir = context.getExternalFilesDir(null) ?: return
                val f = java.io.File(dir, "ad_observations.json")
                if (!f.exists()) return
                val arr = JSONArray(f.readText())
                for (i in until(arr.length())) {
                    val o = arr.getJSONObject(i)
                    val samples = mutableListOf<String>()
                    val sa = o.optJSONArray("samples")
                    if (sa != null) for (j in until(sa.length())) samples.add(sa.optString(j))
                    store.observe(
                        o.optString("packageName"), o.optString("className"),
                        o.optString("reason", "miss"), o.optLong("atEpochMs"), samples
                    )
                }
            } catch (_: Exception) { }
        }

        private fun until(n: Int): IntRange = 0 until n
    }
}
