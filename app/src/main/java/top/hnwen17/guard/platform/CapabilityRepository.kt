package top.hnwen17.guard.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.hnwen17.guard.core.Availability
import top.hnwen17.guard.core.Capability

/**
 * 能力真实状态仓库（QH-P05-04/10）。
 *
 * 唯一事实来源：无障碍 Binder 连接、Shizuku 桥接的实际运行状态。
 * 系统设置里的"已开启"不等于 Binder 正在工作——UI 只认这里的状态。
 * 全部由平台层（Service/Bridge 回调）写入；UI 只读。
 */
object CapabilityRepository {

    data class BridgeState(
        val availability: Availability = Availability.UNCONNECTED,
        val detail: String = ""
    )

    /** QH-P17 安装即用引导已弹标志（进程级，每次冷启重置）。 */
    @Volatile
    var guideShown: Boolean = false

    private val accessibility = MutableStateFlow(BridgeState())
    private val shizuku = MutableStateFlow(BridgeState())

    val accessibilityState: StateFlow<BridgeState> = accessibility.asStateFlow()
    val shizukuState: StateFlow<BridgeState> = shizuku.asStateFlow()

    /** 由 GuardAccessibilityService 生命周期回调写入。 */
    fun onAccessibilityBound(detail: String) {
        android.util.Log.d("RuleRuntime", "CapRepo onAccessibilityBound: $detail")
        accessibility.value = BridgeState(Availability.ACTIVE, detail)
    }

    fun onAccessibilityInterrupted(detail: String) {
        accessibility.value = BridgeState(Availability.LIMITED, detail)
    }

    fun onAccessibilityUnbound(detail: String) {
        accessibility.value = BridgeState(Availability.UNCONNECTED, detail)
    }

    fun onShizukuState(availability: Availability, detail: String) {
        shizuku.value = BridgeState(availability, detail)
    }

    /**
     * 能力 → 真实可用性映射（标准模式口径）：
     * - CLEANER/TOUCH 依赖无障碍 Binder；
     * - SENSOR/JUMP 的真实后端在 P10/P12 实现，桥接状态只反映"通道存在"，不冒充能力可用。
     */
    fun availabilityFor(capability: Capability): Availability = when (capability) {
        Capability.CLEANER, Capability.TOUCH -> accessibility.value.availability
        Capability.SENSOR, Capability.JUMP ->
            if (accessibility.value.availability == Availability.ACTIVE) Availability.LIMITED
            else Availability.UNCONNECTED
    }
}
