package top.hnwen17.guard.core.capability

import top.hnwen17.guard.core.Availability
import top.hnwen17.guard.core.Capability

/**
 * 能力四层状态拆解（QH-P04-01）。
 *
 * 旧 [Availability] 把"用户意图、系统能力、运行状态"压成一个枚举，导致
 * "开关开着但无障碍服务掉线"与"用户主动关闭"无法区分。这里按层拆开：
 *
 * - [request]：用户意图，只有用户操作能改；
 * - [platform]：系统侧能力是否存在（服务连接、Shizuku、VPN 权限等）；
 * - [runtime]：本进程运行状态（连接中/运行/熔断/已停止）；
 * - [reason]/[evidence]：当前状态的可解释原因与可证实证据，绝不凭意图推断成功。
 */
data class CapabilityState(
    val capability: Capability,
    val request: Request = Request.OFF,
    val platform: PlatformSupport = PlatformSupport.UNKNOWN,
    val runtime: Runtime = Runtime.IDLE,
    val reason: Reason = Reason.NONE,
    val evidence: Evidence = Evidence.NONE
) {
    enum class Request { ON, OFF }
    enum class PlatformSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }
    enum class Runtime { IDLE, CONNECTING, RUNNING, CIRCUIT_OPEN, STOPPED }

    /** 平台能力是否就绪；UNKNOWN 不视为就绪（保守门）。 */
    val platformReady: Boolean get() = platform == PlatformSupport.SUPPORTED
    val running: Boolean get() = runtime == Runtime.RUNNING

    fun with(request: Request = this.request, platform: PlatformSupport = this.platform,
             runtime: Runtime = this.runtime, reason: Reason = this.reason,
             evidence: Evidence = this.evidence) =
        copy(request = request, platform = platform, runtime = runtime, reason = reason, evidence = evidence)

    /**
     * 旧 UI 读数适配层：把四层状态折叠为旧 [Availability] 语义。
     * 用户关闭 → DISABLED；意图开但平台未知/不支持 → NEEDS_PERMISSION/UNCONNECTED；
     * 熔断 → ERROR；运行中 → ACTIVE；部分能力受限由调用方按 LIMITED 细化。
     * 全绿条件是"意图+平台+运行"三者同时成立，不因开关打开就返回 ACTIVE。
     */
    fun toLegacyAvailability(): Availability = when {
        request == Request.OFF -> Availability.DISABLED
        runtime == Runtime.CIRCUIT_OPEN -> Availability.ERROR
        platform == PlatformSupport.UNSUPPORTED -> Availability.UNCONNECTED
        platform == PlatformSupport.UNKNOWN -> Availability.NEEDS_PERMISSION
        runtime == Runtime.RUNNING -> Availability.ACTIVE
        runtime == Runtime.CONNECTING -> Availability.NEEDS_PERMISSION
        else -> Availability.UNCONNECTED
    }
}

/** 状态原因：可展示、可测试，禁止用字符串自由拼。 */
enum class Reason {
    NONE,
    USER_REQUEST,
    MISSING_PERMISSION,
    SERVICE_DISCONNECTED,
    SHIZUKU_UNAVAILABLE,
    PLATFORM_UNSUPPORTED,
    REPEATED_FAILURE,
    SAFETY_NO_GO,
    STARTING,
    STOPPED_BY_USER
}

/** 证据：只记录可证实的事实（时间戳 + 种类），不承载大对象。 */
data class Evidence(
    val kind: Kind = Kind.NONE,
    val atMonotonicMs: Long = 0L,
    val detail: String = ""
) {
    enum class Kind { NONE, PROBE_PASSED, PROBE_FAILED, SERVICE_BOUND, SERVICE_UNBOUND, PERMISSION_GRANTED, PERMISSION_DENIED }

    companion object {
        val NONE = Evidence()
    }
}

/**
 * 安全门（P10/P12 复用）：Sensor 恢复未证明、Jump 意图未知时，
 * 标准模式必须能落到 NO_GO，而不是带病运行。
 */
enum class SafetyGate { NOT_APPLICABLE, PENDING, GO, NO_GO }
