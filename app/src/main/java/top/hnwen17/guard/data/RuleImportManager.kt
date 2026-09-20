package top.hnwen17.guard.data

import android.content.Context
import top.hnwen17.guard.core.rules.RuleParser
import top.hnwen17.guard.core.rules.RuleSignatureVerifier
import java.security.PublicKey

/**
 * 规则包导入管理器（QH-P16 本地导入，在线更新需 INTERNET 授权另议）。
 *
 * 流程（ADR-006）：读原始字节 →（可选）ECDSA 验签 → 严格解析 → 登记规则仓库。
 * 验签失败/解析失败/未知 schema 一律拒绝并给出稳定错误码，绝不部分导入。
 */
class RuleImportManager {

    sealed class ImportResult {
        data class Ok(val ruleIds: Set<String>, val packId: String) : ImportResult()
        data class Error(val code: String, val message: String) : ImportResult()
    }

    /**
     * 导入规则包字节。
     * @param signaturePinned 导入方信任的公钥（X.509 编码）；null=允许未签名包（仅 DEBUG/自担风险场景由调用方把关）
     */
    fun import(
        bytes: ByteArray,
        signaturePinned: PublicKey?,
        signature: ByteArray?,
        register: (ids: Set<String>, defaultEnabled: Boolean) -> Unit
    ): ImportResult {
        if (signaturePinned != null) {
            val sig = signature ?: return ImportResult.Error("NO_SIGNATURE", "已签名模式要求签名")
            if (!RuleSignatureVerifier.verify(bytes, sig, signaturePinned)) {
                return ImportResult.Error("BAD_SIGNATURE", "签名验证失败")
            }
        }
        return when (val parsed = RuleParser.parse(bytes)) {
            is RuleParser.RuleParseResult.Ok -> {
                val ids = parsed.pack.rules.map { it.id }.toSet()
                register(ids, parsed.pack.provenance.license == "PROJECT-OWNED" && signaturePinned != null)
                ImportResult.Ok(ids, parsed.pack.id)
            }
            is RuleParser.RuleParseResult.Error -> ImportResult.Error(parsed.code.name, parsed.message)
        }
    }
}
