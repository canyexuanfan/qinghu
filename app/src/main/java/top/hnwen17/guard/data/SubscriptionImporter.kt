package top.hnwen17.guard.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import top.hnwen17.guard.core.rules.RuleParser

/**
 * 阶段3：订阅拉取与安全导入。
 *
 * 支持两种来源格式（均 https 拉取）：
 * 1. 轻护规则包（schemaVersion=1 + rules）：验格式后原样落地，状态由订阅级控制；
 * 2. 李跳跳社区规则（[{hash:"{popup_rules…}"}]，MIT）：走与 PC 转换器同源的安全注入
 *    （敏感标识 / 窗口闸门必须 / maxAttempts=1 / 接受类动作黑名单），并强制 RECORD_ONLY。
 *
 * 隐私：仅访问用户提供的 https 链接；结果存应用私有目录。
 */
object SubscriptionImporter {

    private val ACCEPT_BLOCK = listOf(
        "继续安装", "立即安装", "安装", "安装中", "更新", "立即更新", "立即升级",
        "允许", "同意", "始终允许", "打开", "确定", "立即体验", "立即查看"
    )

    data class Result(
        val ok: Boolean,
        val message: String,
        val packJson: String? = null,
        val packId: String = "",
        val name: String = "",
        val ruleCount: Int = 0,
        val license: String = "",
        val fileName: String? = null
    )

    fun fetch(context: Context, url: String): Result {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) return Result(false, "HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > 8 * 1024 * 1024) return Result(false, "规则包超过 8MiB 上限")
            import(context, bytes, url)
        } catch (e: Exception) {
            Result(false, "网络失败：${e.message ?: "未知错误"}")
        }
    }

    fun import(context: Context, bytes: ByteArray, url: String): Result {
        val text = String(bytes, Charsets.UTF_8).trim()
        return when {
            text.startsWith("[") -> importLitiaotiao(text, url)
            text.startsWith("{") && text.contains("\"rules\"") -> importQinghuPack(text, url)
            else -> Result(false, "无法识别的规则格式（需要轻护规则包或李跳跳社区规则 JSON）")
        }
    }

    /** 轻护 schema 规则包：格式校验后原样落地（点击与否由订阅级状态控制）。 */
    private fun importQinghuPack(text: String, url: String): Result {
        val parsed = RuleParser.parse(text.toByteArray())
        val err = parsed as? RuleParser.RuleParseResult.Error
        if (err != null) return Result(false, "规则包解析失败：${err.code}")
        val pack = (parsed as RuleParser.RuleParseResult.Ok).pack
        val packId = "sub." + Integer.toHexString((pack.id + url).hashCode())
        return Result(true, "轻护规则包：${pack.rules.size} 条", packJson = text,
            packId = packId, name = pack.id, ruleCount = pack.rules.size,
            license = pack.provenance.license, fileName = "pack_$packId.json")
    }

    /** 李跳跳社区规则（MIT JSON）：安全注入后转 RECORD_ONLY 观察舱条目。 */
    private fun importLitiaotiao(text: String, url: String): Result {
        val raw = org.json.JSONArray(text)
        val outRules = mutableListOf<JSONObject>()
        val seen = HashSet<String>()
        var droppedAccept = 0; var droppedBack = 0; var droppedBadGate = 0; var droppedDup = 0
        for (i in 0 until raw.length()) {
            val appObj = raw.optJSONObject(i) ?: continue
            for (hashKey in appObj.keys()) {
                val inner = appObj.optString(hashKey)
                if (inner.isEmpty()) continue
                val prs = try { JSONObject(inner).optJSONArray("popup_rules") ?: continue } catch (_: Exception) { continue }
                for (j in 0 until prs.length()) {
                    val r = prs.optJSONObject(j) ?: continue
                    val gate = r.optString("id").replace(Regex("^[=|-]+"), "").trim()
                    val act = r.optString("action").replace(Regex("^[=|-]+"), "").trim()
                    if (gate.isEmpty() || act.isEmpty()) continue
                    if (ACCEPT_BLOCK.any { act == it || act.contains(it) }) { droppedAccept++; continue }
                    if (act.startsWith("common") || r.optString("action").contains("GLOBAL_ACTION_BACK")) { droppedBack++; continue }
                    val parts = gate.split("&").map { it.replace(Regex("^[=|-]+"), "").trim() }.filter { it.isNotEmpty() }
                    val gatePick = parts.maxByOrNull { it.length } ?: continue
                    if (gatePick.length < 3) { droppedBadGate++; continue }
                    val match = JSONObject()
                    if (Regex("^[a-zA-Z][a-zA-Z0-9_]{2,48}$").matches(act)) match.put("viewId", act)
                    else match.put("textEquals", act)
                    if (Regex("^[a-zA-Z][a-zA-Z0-9_]{3,48}$").matches(gatePick)) match.put("windowViewIdContainsAny", JSONArray(listOf(gatePick)))
                    else match.put("windowTextContainsAny", JSONArray(listOf(gatePick)))
                    val key = match.toString()
                    if (!seen.add(key)) { droppedDup++; continue }
                    val rule = JSONObject()
                        .put("id", "community.sub.${outRules.size}").put("version", 1)
                        .put("provenance", JSONObject()
                            .put("author", "LiTiaoTiao_Custom_Rules 社区（MIT）")
                            .put("license", "MIT").put("source", "community-import@app"))
                        .put("target", JSONObject().put("package", "*").put("minVersionCode", 0).put("maxVersionCode", 999999))
                        .put("page", JSONObject().put("mustNotHave", JSONArray(listOf("sensitive_payment", "password"))))
                        .put("match", match)
                        .put("action", JSONObject().put("type", "RECORD_ONLY").put("maxAttempts", 1).put("cooldownMs", 5000))
                    outRules.add(rule)
                }
            }
        }
        if (outRules.isEmpty()) {
            return Result(false, "转换后可用规则为 0（全部被安全规则丢弃：接受类 $droppedAccept、BACK 类 $droppedBack、弱闸门 $droppedBadGate、重复 $droppedDup）")
        }
        val packId = "sub." + Integer.toHexString(url.hashCode())
        val pack = JSONObject()
            .put("schemaVersion", 1).put("id", packId).put("version", 1)
            .put("provenance", JSONObject().put("author", "LiTiaoTiao_Custom_Rules 社区（MIT）")
                .put("license", "MIT").put("source", url))
            .put("rules", outRules)
        return Result(true,
            "李跳跳社区规则：收下 ${outRules.size} 条（丢弃 接受类$droppedAccept/BACK类$droppedBack/弱闸门$droppedBadGate/重复$droppedDup）",
            packJson = pack.toString(), packId = packId, name = "李跳跳社区规则订阅",
            ruleCount = outRules.size, license = "MIT", fileName = "pack_$packId.json")
    }
}
