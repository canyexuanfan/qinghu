package top.hnwen17.guard.platform.shizuku

import android.content.Context
import android.content.Intent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import top.hnwen17.guard.core.Availability
import top.hnwen17.guard.platform.CapabilityRepository

/**
 * Shizuku 桥接（QH-P05-07/08）。
 *
 * 设计边界：
 * - 普通（standard）模式**不强制 Shizuku**：未安装/未启动/拒绝/死亡都只是状态降级，不崩溃、不假绿；
 * - 状态唯一入口 = CapabilityRepository.onShizukuState；
 * - 重连受限且按需：仅在 UI 请求授权或应用回到前台时 ping，不做常驻轮询；
 * - 死亡回调（binder Died）→ 立即降级为 UNCONNECTED，等待下次按需重连。
 *
 * 引用的制品：dev.rikka.shizuku:api/provider 13.1.5（MIT，R01 准入，见 reference/legal/R01.md）。
 */
object ShizukuBridge {

    const val REQUEST_CODE = 45001

    private var listenersAttached = false
    private var receivedListener: Shizuku.OnBinderReceivedListener? = null
    private var deadListener: Shizuku.OnBinderDeadListener? = null
    private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null

    /** 应用 onCreate 调用一次；重复调用幂等。 */
    fun attach(context: Context) {
        if (listenersAttached) return
        listenersAttached = true
        val appContext = context.applicationContext

        receivedListener = Shizuku.OnBinderReceivedListener {
            when {
                !Shizuku.pingBinder() -> CapabilityRepository.onShizukuState(Availability.UNCONNECTED, "binder 无响应")
                Shizuku.isPreV11() -> CapabilityRepository.onShizukuState(Availability.UNCONNECTED, "Shizuku 版本过旧（pre-v11）")
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED ->
                    CapabilityRepository.onShizukuState(Availability.ACTIVE, "已授权 v${Shizuku.getVersion()}")
                else -> CapabilityRepository.onShizukuState(Availability.NEEDS_PERMISSION, "已连接，等待授权")
            }
        }
        deadListener = Shizuku.OnBinderDeadListener {
            CapabilityRepository.onShizukuState(Availability.UNCONNECTED, "binder 已死亡")
        }
        permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    CapabilityRepository.onShizukuState(Availability.ACTIVE, "已授权 v${Shizuku.getVersion()}")
                } else {
                    CapabilityRepository.onShizukuState(Availability.NEEDS_PERMISSION, "用户拒绝授权")
                }
            }
        }
        Shizuku.addBinderReceivedListener(receivedListener!!)
        Shizuku.addBinderDeadListener(deadListener!!)
        Shizuku.addRequestPermissionResultListener(permissionListener!!)

        // 初始状态：立即探测一次（未安装时 provider 查询会抛 IllegalStateException）
        probeInitial(appContext)
    }

    private fun probeInitial(context: Context) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                CapabilityRepository.onShizukuState(Availability.ACTIVE, "已授权 v${Shizuku.getVersion()}")
            } else {
                CapabilityRepository.onShizukuState(Availability.UNCONNECTED, "未检测到 Shizuku 服务")
            }
        } catch (_: IllegalStateException) {
            CapabilityRepository.onShizukuState(Availability.UNCONNECTED, "未安装 Shizuku 管理器")
        } catch (_: Exception) {
            CapabilityRepository.onShizukuState(Availability.UNCONNECTED, "Shizuku 探测失败")
        }
    }

    /** Shizuku 管理器是否已安装（queries 已声明包可见性）。 */
    fun managerInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo("moe.shizuku.manager", 0)
        true
    } catch (_: Exception) {
        false
    }

    /** 发起授权请求；返回 false 表示当前无法发起（未安装/未启动），由 UI 给出指引。 */
    fun requestPermission(): Boolean = try {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                CapabilityRepository.onShizukuState(Availability.ACTIVE, "已授权 v${Shizuku.getVersion()}")
                true
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
                true
            }
        } else false
    } catch (_: Exception) {
        false
    }

    /**
     * QH-P05-09：绑定 GuardUserService（固定探测接口）。
     * @return false=当前无法绑定（未安装/未启动），调用方提示，不重试轰炸
     */
    fun bindGuardUserService(context: Context): Boolean = try {
        if (Shizuku.pingBinder()) {
            val args = Shizuku.UserServiceArgs(
                android.content.ComponentName(context, GuardUserService::class.java)
            ).processNameSuffix("guard_user").version(1)
            Shizuku.bindUserService(args, object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {
                    CapabilityRepository.onShizukuState(Availability.ACTIVE, "user service bound v${Shizuku.getVersion()}")
                }
                override fun onServiceDisconnected(name: android.content.ComponentName) {
                    CapabilityRepository.onShizukuState(Availability.UNCONNECTED, "user service disconnected")
                }
            })
            true
        } else false
    } catch (_: Exception) { false }

    fun detach() {
        if (!listenersAttached) return
        listenersAttached = false
        receivedListener?.let { Shizuku.removeBinderReceivedListener(it) }
        deadListener?.let { Shizuku.removeBinderDeadListener(it) }
        permissionListener?.let { Shizuku.removeRequestPermissionResultListener(it) }
        CapabilityRepository.onShizukuState(Availability.UNCONNECTED, "桥接已分离")
    }

    /** 供应用回到前台时按需重探（不做常驻轮询）。 */
    fun reprobe() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    CapabilityRepository.onShizukuState(Availability.ACTIVE, "已授权 v${Shizuku.getVersion()}")
                } else {
                    CapabilityRepository.onShizukuState(Availability.NEEDS_PERMISSION, "已连接，等待授权")
                }
            } else {
                CapabilityRepository.onShizukuState(Availability.UNCONNECTED, "未检测到 Shizuku 服务")
            }
        } catch (_: Exception) {
            CapabilityRepository.onShizukuState(Availability.UNCONNECTED, "未安装 Shizuku 管理器")
        }
    }
}
