package top.hnwen17.guard.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** QH-P16：ECDSA P-256 签名验证（标准平台实现）。 */
class RuleSignatureVerifierTest {

    private fun generateP256(): java.security.KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    @Test
    fun `签名验证-正确签名通过-篡改失败`() {
        val kp = generateP256()
        val data = "rule pack payload bytes".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(kp.private)
            update(data)
        }.sign()
        assertTrue(RuleSignatureVerifier.verify(data, sig, kp.public))
        val tampered = data.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(RuleSignatureVerifier.verify(tampered, sig, kp.public)) // 篡改字节必须验证失败
        assertFalse(RuleSignatureVerifier.verify(data, sig.copyOf().also { it[0] = 0 }, kp.public))
    }

    @Test
    fun `错误算法或密钥-验证失败不抛出`() {
        val kp1 = generateP256()
        val kp2 = generateP256()
        val data = "x".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(kp1.private); update(data) }.sign()
        assertFalse(RuleSignatureVerifier.verify(data, sig, kp2.public)) // 他人密钥验证必须失败
    }

    @Test
    fun `X509公钥编码往返`() {
        val kp = generateP256()
        val encoded = kp.public.encoded
        val restored = RuleSignatureVerifier.publicKeyFromX509(encoded)
        assertNotNull(restored)
        val data = "payload".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(data) }.sign()
        assertTrue(RuleSignatureVerifier.verify(data, sig, restored!!))
    }

    @Test
    fun `十六进制解码`() {
        val bytes = RuleSignatureVerifier.fromHex("DEADBEEF")
        assertEquals(4, bytes.size)
        assertEquals(-34, bytes[0].toInt())
    }
}
