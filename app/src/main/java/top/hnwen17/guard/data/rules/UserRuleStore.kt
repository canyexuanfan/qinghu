package top.hnwen17.guard.data.rules

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户自建跳过规则仓库（可视化向导生成，零代码）。
 *
 * 安全护栏（生成规则时自动附加，用户无需理解）：
 * - 按钮文字精确匹配（textEquals），不做模糊包含——避免通用词误伤；
 * - 默认限定单一 App（target.package），全局需用户主动选择；
 * - 点击后验（absentTextEquals）：按钮未消失=未生效，防假成功；
 * - 敏感窗口（支付/登录）由运行时 SafetyExclusions 统一豁免；
 * - 视图 ID 证据另需 clickable=true。
 */
class UserRuleStore {

    data class UserRule(
        val id: String,
        val packageName: String, // "*" = 所有应用
        val buttonText: String,
        val matchKind: String,   // TEXT / VIEW_ID / DESC
        val createdAt: Long,
        val enabled: Boolean = true,
        val note: String = ""    // 来源窗口类名，便于识别
    )

    private val rules = mutableListOf<UserRule>()
    private val _all = MutableStateFlow<List<UserRule>>(emptyList())
    val all: StateFlow<List<UserRule>> = _all.asStateFlow()

    @Synchronized
    fun add(packageName: String, buttonText: String, matchKind: String, note: String): UserRule {
        // 同目标同证据去重：重复创建等于更新
        rules.removeAll { it.packageName == packageName && it.buttonText == buttonText && it.matchKind == matchKind }
        val r = UserRule(
            id = "user." + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8),
            packageName = packageName,
            buttonText = buttonText,
            matchKind = matchKind,
            createdAt = System.currentTimeMillis(),
            enabled = true,
            note = note.take(80)
        )
        rules.add(r)
        _all.value = rules.toList()
        return r
    }

    /** 持久化恢复：保留原 id 与启用状态。 */
    @Synchronized
    fun restore(id: String, packageName: String, buttonText: String, matchKind: String,
                createdAt: Long, enabled: Boolean, note: String) {
        if (rules.any { it.id == id }) return
        rules.add(UserRule(id, packageName, buttonText, matchKind, createdAt, enabled, note.take(80)))
        _all.value = rules.toList()
    }

    @Synchronized
    fun remove(id: String) {
        rules.removeAll { it.id == id }
        _all.value = rules.toList()
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        val i = rules.indexOfFirst { it.id == id }
        if (i >= 0) {
            rules[i] = rules[i].copy(enabled = enabled)
            _all.value = rules.toList()
        }
    }

    /**
     * 生成规则包字节（仅含启用的规则）；无启用规则返回 null（调用方删除文件）。
     * 包结构与内置/订阅包同 schema，RuleParser 直接解析。
     */
    @Synchronized
    fun packBytes(): ByteArray? {
        val enabled = rules.filter { it.enabled }
        if (enabled.isEmpty()) return null
        val arr = JSONArray()
        for (r in enabled) {
            val rule = JSONObject()
                .put("id", r.id)
                .put("version", 1)
                .put("provenance", JSONObject()
                    .put("author", "user").put("license", "PROJECT-OWNED").put("source", "user-created"))
                .put("target", JSONObject()
                    .put("package", r.packageName).put("minVersionCode", 0).put("maxVersionCode", 999999))
                .put("match", when (r.matchKind) {
                    "VIEW_ID" -> JSONObject().put("viewId", r.buttonText).put("clickable", true)
                    "DESC" -> JSONObject().put("descContains", r.buttonText)
                    else -> JSONObject().put("textEquals", r.buttonText)
                })
                .put("action", JSONObject()
                    .put("type", "CLICK_VERIFIED_NODE").put("maxAttempts", 2).put("cooldownMs", 3000))
            if (r.matchKind == "TEXT") {
                rule.put("postcondition", JSONObject()
                    .put("absentTextEquals", r.buttonText).put("timeoutMs", 1000))
            }
            arr.put(rule)
        }
        val pack = JSONObject()
            .put("schemaVersion", 1)
            .put("id", "qinghu.user.rules")
            .put("version", System.currentTimeMillis() % Int.MAX_VALUE)
            .put("provenance", JSONObject()
                .put("author", "user").put("license", "PROJECT-OWNED").put("source", "user-created"))
            .put("rules", arr)
        return pack.toString().toByteArray(Charsets.UTF_8)
    }

    /** 向导证据选项：把原始观察样本翻译成用户能看懂的选项。 */
    data class SampleOption(val kind: String, val value: String, val label: String, val recommended: Boolean)

    companion object {
        private val CLASS_LIKE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){1,}$")
        private val SKIP_TEXT = Regex("^(跳过(广告)?(\\s*\\d{1,3})?|Skip)$", RegexOption.IGNORE_CASE)
        private const val FILE = "user_rules.json"

        /**
         * 把观察样本翻译成小白可读的证据选项：
         * - 类名样式串（android.widget.FrameLayout）对用户无意义，不出现；
         * - 「跳过/跳过 N/跳过广告/Skip」标记为推荐并排序最前；
         * - #viewId / @desc 保留为进阶选项，附中文说明。
         */
        fun presentableSamples(samples: List<String>): List<SampleOption> {
            val out = mutableListOf<SampleOption>()
            for (raw in samples.distinct()) {
                val s = raw.trim()
                if (s.isEmpty()) continue
                when {
                    s.startsWith("#") -> {
                        val v = s.substringAfter("#").trim()
                        if (v.isNotEmpty()) out.add(SampleOption("VIEW_ID", v, "控件标识：$v（进阶选项）",
                            v.lowercase().contains("skip") || v.lowercase().contains("close")))
                    }
                    s.startsWith("@") -> {
                        val v = s.substringAfter("@").trim()
                        if (v.isNotEmpty()) out.add(SampleOption("DESC", v, "按钮描述：$v",
                            SKIP_TEXT.matches(v) || v.contains("关闭广告")))
                    }
                    else -> {
                        if (CLASS_LIKE.matches(s)) continue
                        out.add(SampleOption("TEXT", s,
                            if (SKIP_TEXT.matches(s)) "「$s」——推荐，这就是跳过按钮" else "页面文字：$s",
                            SKIP_TEXT.matches(s)))
                    }
                }
            }
            return out.sortedBy { when { it.recommended && it.kind == "TEXT" -> 0; it.recommended -> 1; else -> 2 } }
        }

        private val RISKY_COMMON_WORDS = setOf("确定", "取消", "知道了", "我知道了", "确认", "是", "否", "好的")

        /** 向导提示：证据文字是否属于通用词（UI 提示误触风险，不阻止创建）。 */
        fun isRiskyCommonWord(text: String): Boolean = text.trim() in RISKY_COMMON_WORDS

        fun save(context: Context, store: UserRuleStore) {
            try {
                val dir = context.getExternalFilesDir(null)?.resolve("user_rules")?.apply { mkdirs() } ?: return
                val bytes = store.packBytes()
                val f = java.io.File(dir, FILE)
                if (bytes == null) f.delete() else f.writeBytes(bytes)
            } catch (_: Exception) { }
        }

        fun load(context: Context, store: UserRuleStore) {
            try {
                val f = context.getExternalFilesDir(null)?.resolve("user_rules")?.resolve(FILE) ?: return
                if (!f.exists()) return
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    store.restore(
                        o.getString("id"), o.getString("packageName"), o.getString("buttonText"),
                        o.optString("matchKind", "TEXT"), o.optLong("createdAt"),
                        o.optBoolean("enabled", true), o.optString("note")
                    )
                }
            } catch (_: Exception) { }
        }
    }
}
