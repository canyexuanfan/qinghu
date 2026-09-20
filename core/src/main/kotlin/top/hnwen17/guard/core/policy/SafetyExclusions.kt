package top.hnwen17.guard.core.policy

import top.hnwen17.guard.core.rules.SnapshotNode

/**
 * 安全排除（QH-P07-07，core 侧防线）。
 *
 * 匹配器执行前先排除敏感上下文：支付/登录/密码/授权类界面一律不做主动点击。
 * app 采集层负责第一道防线（密码框等节点不进快照、不记录文本）；
 * 本对象在 core 内对快照做防御性二次判定——两道防线独立失效才算失效。
 *
 * 判定只依据 viewId/类名的**结构化标识**，不读也不保存用户文本内容。
 */
object SafetyExclusions {

    /** 结构化敏感标识（小写包含匹配；仅作用于 id/类名，不读用户文本）。 */
    private val SENSITIVE_ID_TOKENS = listOf(
        "password", "passwd", "pwd", "pin_", "_pin", "captcha",
        "payment", "pay_", "_pay", "checkout", "bankcard", "creditcard",
        "otp", "smscode", "verify_code", "login_"
    )

    /** 包名敏感 token（跳转目标判定用）：支付/登录/银行类。 */
    private val SENSITIVE_PACKAGE_TOKENS = listOf(
        "pay", "payment", "wallet", "bank", "login", "auth", "alipay", "tenpay", "unionpay"
    )

    /** 目标包名是否敏感（跳转放行判定）。 */
    fun isSensitivePackageName(packageName: String): Boolean {
        val name = packageName.lowercase()
        return SENSITIVE_PACKAGE_TOKENS.any { name.contains(it) }
    }

    /** 单节点是否结构化敏感：密码标志或 id/类名命中敏感词。 */
    fun isSensitiveNode(node: SnapshotNode): Boolean {
        if (node.isPassword) return true
        val id = node.viewId?.lowercase() ?: return false
        if (SENSITIVE_ID_TOKENS.any { id.contains(it) }) return true
        val cls = node.className?.lowercase() ?: return false
        return cls.contains("password")
    }

    /**
     * 整窗是否敏感：任一可见节点敏感即整窗排除主动动作。
     * 有界遍历（快照本身 ≤256 节点），首个命中即返回。
     */
    fun windowHasSensitiveContext(root: SnapshotNode): Boolean {
        if (isSensitiveNode(root)) return true
        for (child in root.children) {
            if (windowHasSensitiveContext(child)) return true
        }
        return false
    }

    /**
     * 终判：主动点击类规则在敏感窗口一律否决；
     * RECORD_ONLY 不受影响（观察无副作用）。
     */
    fun allowsAction(actionType: UiRuleActionType, sensitiveWindow: Boolean): Boolean =
        !(sensitiveWindow && actionType == UiRuleActionType.CLICK_VERIFIED_NODE)

    /** 与规则 schema 解耦的动作类型影子（避免 core.policy 依赖 core.rules 内部枚举方向）。 */
    enum class UiRuleActionType { CLICK_VERIFIED_NODE, RECORD_ONLY }
}
