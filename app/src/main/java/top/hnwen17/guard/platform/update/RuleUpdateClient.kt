package top.hnwen17.guard.platform.update

import android.content.Context
import top.hnwen17.guard.data.RuleImportManager
import java.net.URL
import java.security.PublicKey

/**
 * 在线规则更新客户端（QH-P16，用户显式 opt-in）。
 *
 * 合同（ADR-003）：
 * - 仅 HTTPS；仅用户开启时拉取；无后台轮询（每次手动/启动触发一次）；
 * - 拉取内容必须过 ECDSA 验签+严格解析，验证失败整包拒绝并提示；
 * - 成功后持久化到外部 files/rules/（RuleRuntime 下次加载纳入，默认停用可设置启用）。
 */
class RuleUpdateClient(private val context: Context) {

    sealed class Result {
        data class Ok(val ruleIds: Set<String>, val bytes: Int) : Result()
        data class Error(val stage: Stage, val message: String) : Result()

        enum class Stage { NETWORK, SIGNATURE, PARSE, IMPORT }
    }

    fun fetchAndImport(
        url: String,
        signatureUrl: String?,
        pinnedPublicKey: PublicKey,
        importManager: RuleImportManager
    ): Result {
        if (!url.startsWith("https://")) return Result.Error(Result.Stage.NETWORK, "仅允许 HTTPS 来源")
        return try {
            val bytes = download(url) ?: return Result.Error(Result.Stage.NETWORK, "下载失败")
            val sig = if (signatureUrl != null) {
                download(signatureUrl) ?: return Result.Error(Result.Stage.SIGNATURE, "签名下载失败")
            } else null
            // 先严格解析（拒绝未知 schema/恶意结构），再验签
            val importResult = importManager.import(bytes, pinnedPublicKey, sig) { _, _ -> }
            when (importResult) {
                is RuleImportManager.ImportResult.Ok -> {
                    persist(context, url, bytes)
                    Result.Ok(importResult.ruleIds, bytes.size)
                }
                is RuleImportManager.ImportResult.Error -> Result.Error(Result.Stage.PARSE, "${importResult.code}: ${importResult.message}")
            }
        } catch (e: Exception) {
            Result.Error(Result.Stage.NETWORK, e.message?.take(80) ?: "unknown")
        }
    }

    private fun download(url: String): ByteArray? = try {
        val conn = URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false // 不跟随重定向：固定源，防降级
        if (conn.responseCode in 200..299) conn.inputStream.use { it.readBytes() } else null
    } catch (_: Exception) {
        null
    }

    /** 持久化到外部 files/rules/online_last.json（RuleRuntime 外部目录加载纳入）。 */
    private fun persist(context: Context, sourceUrl: String, bytes: ByteArray) {
        try {
            val dir = context.getExternalFilesDir(null)?.resolve("rules")?.apply { mkdirs() } ?: return
            java.io.File(dir, "online_last.json").writeBytes(bytes)
            java.io.File(dir, "online_last.json.src").writeText(sourceUrl)
        } catch (_: Exception) { }
    }
}
