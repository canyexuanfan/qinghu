package top.hnwen17.guard.core.rules

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.PublicKey
import java.security.KeyFactorySpi

/**
 * 规则包签名验证（QH-P16，ADR-006）。
 *
 * 算法白名单：ECDSA（SHA256withECDSA），P-256 曲线——仅使用 java.security 标准平台实现。
 * 验签对象=精确原始字节（不得重新格式化后验签）。
 * 公钥以 X.509 SubjectPublicKeyInfo 编码字节注入（内置或导入时登记），不硬编码私钥。
 */
object RuleSignatureVerifier {

    /** 从 X.509 编码字节恢复公钥。失败返回 null（调用方拒绝该来源）。 */
    fun publicKeyFromX509(encoded: ByteArray): PublicKey? = try {
        val kf = KeyFactory.getInstance("EC")
        kf.generatePublic(X509EncodedKeySpec(encoded))
    } catch (_: Exception) {
        null
    }

    /** 验证：data 的 ECDSA-SHA256 签名是否出自 publicKey。任何异常=验证失败。 */
    fun verify(data: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean = try {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(publicKey)
        sig.update(data)
        sig.verify(signature)
    } catch (_: Exception) {
        false
    }

    /** 便捷：十六进制公钥/签名字符串解码（导入清单常用格式）。 */
    fun fromHex(hex: String): ByteArray {
        val clean = hex.removePrefix("0x")
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
