package top.hnwen17.guard.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阶段3：规则订阅仓库（三级状态：DISABLED 停用 / OBSERVE 仅观察 / ENABLED 启用）。
 *
 * - 新添加的订阅默认 OBSERVE（仅观察：命中只记录，零界面副作用）——「不乱点」的落点；
 * - 内置规则集独立开关以 id="builtin" 伪条目持久化（clickEnabled=启用与否）；
 * - 持久化：应用私有目录 subscriptions.json；转换/下载的规则包文件在 files/subscriptions/ 下。
 */
class SubscriptionsStore {
    data class SubEntry(
        val id: String,
        val name: String,
        val url: String = "",
        val state: String = STATE_OBSERVE,          // DISABLED / OBSERVE / ENABLED
        val clickEnabled: Boolean = false,          // builtin 条目=内置开关；订阅条目=启用态类别开关
        val lastUpdateAtMs: Long = 0L,
        val ruleCount: Int = 0,
        val license: String = "",
        val fileName: String? = null                // files/subscriptions/ 下的包文件名
    )

    private val subs = mutableListOf<SubEntry>()
    private val _all = MutableStateFlow<List<SubEntry>>(emptyList())
    val all: StateFlow<List<SubEntry>> = _all.asStateFlow()

    private val _builtinEnabled = MutableStateFlow(true)
    val builtinEnabled: StateFlow<Boolean> = _builtinEnabled.asStateFlow()
    private val _externalEnabled = MutableStateFlow(true)
    val externalEnabled: StateFlow<Boolean> = _externalEnabled.asStateFlow()

    @Synchronized fun upsert(entry: SubEntry) {
        val i = subs.indexOfFirst { it.id == entry.id }
        if (i >= 0) subs[i] = entry else subs.add(entry)
        publish()
    }

    @Synchronized fun getSub(id: String): SubEntry? = subs.firstOrNull { it.id == id }

    @Synchronized fun remove(id: String) {
        subs.removeAll { it.id == id }
        publish()
    }

    @Synchronized fun setBuiltinEnabled(v: Boolean) {
        _builtinEnabled.value = v
        upsert(SubEntry(id = "builtin", name = "内置规则集",
            state = if (v) "ENABLED" else "DISABLED", clickEnabled = v))
    }

    @Synchronized fun setExternalEnabled(v: Boolean) {
        _externalEnabled.value = v
    }

    @Synchronized fun setBuiltinEnabledInitial(v: Boolean) {
        _builtinEnabled.value = v
    }

    @Synchronized fun setExternalEnabledInitial(v: Boolean) {
        _externalEnabled.value = v
    }

    private fun publish() {
        _all.value = subs.filter { it.id != "builtin" }
        _builtinEnabled.value = subs.firstOrNull { it.id == "builtin" }?.clickEnabled ?: true
    }

    companion object {
        const val STATE_DISABLED = "DISABLED"
        const val STATE_OBSERVE = "OBSERVE"
        const val STATE_ENABLED = "ENABLED"
        const val RECOMMENDED_ID = "qinghu.community.litiaotiao-custom-rules"
        const val RECOMMENDED_URL =
            "https://raw.githubusercontent.com/canyexuanfan/qinghu/main/rules-dist/litiaotiao-custom-rules.json"

        fun load(context: Context, store: SubscriptionsStore) {
            try {
                val f = java.io.File(context.filesDir, "subscriptions.json")
                if (!f.exists()) {
                    store.upsert(SubEntry(id = "builtin", name = "内置规则集",
                        state = "ENABLED", clickEnabled = true))
                    store.upsert(SubEntry(id = RECOMMENDED_ID, name = "轻护推荐订阅（开屏/弹窗 · MIT）",
                        url = RECOMMENDED_URL, state = STATE_OBSERVE, license = "MIT"))
                    store.publish()
                    return
                }
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getString("id")
                    if (id == "builtin") store.setBuiltinEnabledInitial(o.optBoolean("clickEnabled", true))
                    if (id == "external") store.setExternalEnabledInitial(o.optBoolean("clickEnabled", true))
                    else store.upsert(SubEntry(
                        id = id, name = o.getString("name"), url = o.optString("url"),
                        state = o.optString("state", STATE_OBSERVE),
                        clickEnabled = o.optBoolean("clickEnabled", false),
                        lastUpdateAtMs = o.optLong("lastUpdateAtMs", 0L),
                        ruleCount = o.optInt("ruleCount", 0),
                        license = o.optString("license", ""),
                        fileName = o.optString("fileName").ifEmpty { null }
                    ))
                }
                store.publish()
            } catch (_: Exception) { }
        }

        fun save(context: Context, store: SubscriptionsStore) {
            try {
                val arr = JSONArray()
                for (e in store.all.value) arr.put(JSONObject()
                    .put("id", e.id).put("name", e.name).put("url", e.url)
                    .put("state", e.state).put("clickEnabled", e.clickEnabled)
                    .put("lastUpdateAtMs", e.lastUpdateAtMs).put("ruleCount", e.ruleCount)
                    .put("license", e.license).put("fileName", e.fileName ?: ""))
                val b = store.builtinEnabled.value
                arr.put(JSONObject().put("id", "builtin").put("name", "内置规则集")
                    .put("state", if (b) "ENABLED" else "DISABLED").put("clickEnabled", b))
                val e2 = store.externalEnabled.value
                arr.put(JSONObject().put("id", "external").put("name", "外置规则集")
                    .put("state", if (e2) "ENABLED" else "DISABLED").put("clickEnabled", e2))
                java.io.File(context.filesDir, "subscriptions.json").writeText(arr.toString())
            } catch (_: Exception) { }
        }
    }
}
