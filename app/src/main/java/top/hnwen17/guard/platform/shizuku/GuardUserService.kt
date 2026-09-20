package top.hnwen17.guard.platform.shizuku

import android.content.Context
import android.os.Build
import top.hnwen17.guard.IGuardUserService

/**
 * QH-P05-09：Shizuku UserService 实现（固定探测操作）。
 *
 * 由 Shizuku 以 shell/uid 身份拉起（构造器带 Context 为 Shizuku 约定）。
 * 仅实现 interfaceVersion/selfTest 两个只读探测操作——
 * 传感器抑制/跳转控制等增强操作在 P10/P12 安全门验证后另行接口版本化，
 * 不提供任意 shell、文件访问或 Intent 转发能力。
 */
class GuardUserService(private val context: Context) : IGuardUserService.Stub() {

    override fun interfaceVersion(): Int = INTERFACE_VERSION

    override fun selfTest(): String = buildString {
        append("api=").append(Build.VERSION.SDK_INT)
        append(";fingerprint=").append(Build.FINGERPRINT.take(80))
        append(";abi=").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "")
        append(";uid=").append(android.os.Process.myUid())
    }

    companion object {
        const val INTERFACE_VERSION = 1
    }
}
