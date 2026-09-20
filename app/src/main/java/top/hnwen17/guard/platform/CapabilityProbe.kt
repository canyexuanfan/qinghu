package top.hnwen17.guard.platform

import android.os.Build

/**
 * 设备能力自检（QH-P05-10）。
 *
 * 把设备 build/API/ROM 指纹与桥接版本关联成可持久化快照——
 * ROM 升级使旧结论失效（fingerprint 变化即失效），不依赖通用品牌名宣称支持。
 */
object CapabilityProbe {

    data class ProbeResult(
        val apiLevel: Int,
        val fingerprint: String,
        val abi: String,
        val shizukuVersion: Int?,     // null=桥接未激活
        val atNanos: Long
    )

    fun probe(shizukuVersion: Int?, atNanos: Long): ProbeResult = ProbeResult(
        apiLevel = Build.VERSION.SDK_INT,
        fingerprint = (Build.FINGERPRINT ?: "").take(120),
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "",
        shizukuVersion = shizukuVersion,
        atNanos = atNanos
    )

    /** ROM 升级失效判定：指纹变化 → 旧探针结论不再采信。 */
    fun stillValid(recorded: ProbeResult, currentFingerprint: String): Boolean =
        recorded.fingerprint == currentFingerprint
}
