package top.hnwen17.guard.platform.jump

import top.hnwen17.guard.core.session.MonotonicClock

/**
 * 跳转拦截判定器（QH-P12/P13，纯逻辑可测）。
 *
 * 合同（PRD/技术方案）：
 * - 默认只**观测**；阻止必须满足：源应用用户显式开启 Jump + 非敏感目标 + 非用户主动路径证据不足时放行；
 * - "时间窗口分数不是证据"——不使用固定 risk>=70 之类的自动拦截通行证；
 * - 敏感流程（支付/登录/银行）一律放行并记录；
 * - 记录 observedSource/confidence，不把最近前台 App 直接当可信调用者。
 *
 * 判定依据（无障碍可见信号，保守）：
 * - 源应用前台 ≥[MIN_FOREGROUND_MS] 且未发生用户交互间隙 → 视为"非主动跳转"候选；
 * - 目标包命中敏感 token → 一律放行。
 */
class JumpInterceptor(
    private val clock: MonotonicClock,
    private val minForegroundMs: Long = 800L
) {

    data class Decision(val action: Action, val reason: String)

    enum class Action { OBSERVE, BLOCK_BACK, ALLOW_SENSITIVE, ALLOW_USER_PATH }

    private var foregroundPackage: String? = null
    private var foregroundSinceMs: Long = 0L
    private var lastInteractionMs: Long = 0L

    /** 窗口事件喂入：维护前台包与驻留时长。 */
    fun onForeground(pkg: String?, nowMs: Long) {
        pkg ?: return
        if (pkg != foregroundPackage) {
            foregroundPackage = pkg
            foregroundSinceMs = nowMs
        }
    }

    /** 用户交互信号（无障碍 KEY 事件/触摸代理）：证明用户在场。 */
    fun onUserInteraction(nowMs: Long) {
        lastInteractionMs = nowMs
    }

    /**
     * 跳转候选判定。
     * @param fromPackage 触发跳转前的前台包（我们的记录）
     * @param toPackage   跳转目标包
     * @param jumpEnabled 源应用是否被用户显式开启 Jump 保护
     * @param targetSensitive 目标命中敏感 token（支付/登录/银行）
     */
    fun decide(
        fromPackage: String,
        toPackage: String,
        jumpEnabled: Boolean,
        targetSensitive: Boolean
    ): Decision {
        if (toPackage == fromPackage) return Decision(Action.OBSERVE, "same package")
        val heldMs = clock.nowMs() - foregroundSinceMs
        // 驻留极短：可能是用户快速切换（用户路径），放行
        if (heldMs < minForegroundMs) return Decision(Action.ALLOW_USER_PATH, "foreground ${heldMs}ms < $minForegroundMs")
        // 敏感目标一律放行（支付/登录优先于拦截）
        if (targetSensitive) return Decision(Action.ALLOW_SENSITIVE, "sensitive target")
        // 未显式开启：只观测
        if (!jumpEnabled) return Decision(Action.OBSERVE, "jump not enabled for $fromPackage")
        // 全部满足：拦截（BACK 返回源应用）
        return Decision(Action.BLOCK_BACK, "auto jump $fromPackage -> $toPackage blocked")
    }

    /** 供记录：observedSource 与置信度口径。 */
    fun observationContext(): Pair<String?, Long> = foregroundPackage to (clock.nowMs() - foregroundSinceMs)
}
