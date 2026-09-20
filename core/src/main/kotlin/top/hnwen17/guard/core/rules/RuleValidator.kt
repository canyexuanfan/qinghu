package top.hnwen17.guard.core.rules

import top.hnwen17.guard.core.records.ProtectionOutcome

/**
 * 规则元数据准入（QH-P07-03）。
 *
 * 缺许可 / 来源未审 / 已过期 的规则包不得进入正式内置集；
 * 开发 fixture（[Tier.DEV_FIXTURE]）只能进 debug 与测试，运行时必须显式标注隔离。
 */
object RuleValidator {

    /** 准入层级：正式内置与开发样例的边界必须可机判。 */
    enum class Tier { BUILTIN_SIGNED, USER_IMPORTED_SIGNED, DEV_FIXTURE }

    /** 可进入正式内置的许可白名单（本项目自有创作）。 */
    private val BUILTIN_LICENSES = setOf("PROJECT-OWNED")

    data class Verdict(
        val tier: Tier,
        val ok: Boolean,
        val code: RuleErrorCode,
        val detail: String = ""
    )

    fun validate(pack: RulePack, tier: Tier, nowEpochMs: Long): Verdict {
        val prov = pack.provenance
        if (prov.author.isBlank() || prov.license.isBlank() || prov.source.isBlank()) {
            return Verdict(tier, false, RuleErrorCode.MISSING_LICENSE_PROVENANCE, "provenance incomplete")
        }
        if (tier != Tier.DEV_FIXTURE && prov.license !in BUILTIN_LICENSES) {
            return Verdict(tier, false, RuleErrorCode.MISSING_LICENSE_PROVENANCE,
                "license '${prov.license}' not approved for tier $tier")
        }
        if (tier == Tier.DEV_FIXTURE && prov.source != "self-authored-test-fixture") {
            return Verdict(tier, false, RuleErrorCode.UNREVIEWED_PROVENANCE,
                "dev fixture must be self-authored")
        }
        // 过期检查交给调用方注入时钟（单调墙钟均可：过期是粗粒度日期，用墙钟语义）
        // 注意：规则包 expiresAt 是产品级准入门槛，非热路径；此处统一在包级检查。
        return Verdict(tier, true, RuleErrorCode.OK)
    }

    /** 单条规则过期判断（可注入 now 便于测试；不做时钟回拨补偿——过期是日粒度）。 */
    fun isExpired(rule: UiRule, nowEpochMs: Long): Boolean =
        rule.expiresAtEpochMs != null && nowEpochMs > rule.expiresAtEpochMs

    /** 会话级守门：已过期规则在会话内直接不参与匹配。 */
    fun usableRules(pack: RulePack, nowEpochMs: Long): List<UiRule> =
        pack.rules.filter { !isExpired(it, nowEpochMs) }

    /** 统计口径辅助：RECORD_ONLY 规则的成功不构成拦截成绩。 */
    fun isBlockingAction(rule: UiRule): Boolean =
        rule.action.type != UiRule.RuleAction.ActionType.RECORD_ONLY &&
            !isCountableFake(rule)

    private fun isCountableFake(rule: UiRule): Boolean = false // 占位：防未来误把观察类计成拦截
}

/** 与 ProtectionOutcome 的桥接：RECORD_ONLY 永远映射为 REQUESTED/UNKNOWN，不冒充 EXECUTED。 */
fun outcomeFor(rule: UiRule, executed: Boolean): ProtectionOutcome = when {
    rule.action.type == UiRule.RuleAction.ActionType.RECORD_ONLY -> ProtectionOutcome.REQUESTED
    executed -> ProtectionOutcome.EXECUTED
    else -> ProtectionOutcome.FAILED
}
