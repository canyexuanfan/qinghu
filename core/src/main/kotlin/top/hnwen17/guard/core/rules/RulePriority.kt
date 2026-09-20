package top.hnwen17.guard.core.rules

/**
 * 规则冲突优先级（QH-P07-08）。
 *
 * 同会话多规则命中时选择**最小干预**：
 * 1. 允许列表（mustNotHave 命中/RECORD_ONLY 类）性质的保护优先于主动点击；
 * 2. 主动动作里，条件更具体（更多匹配字段）者优先；
 * 3. 仍并列时按 (rule.id, rule.version) 字典序稳定排序，保证可重放；
 * 4. 同一规则在同会话内重复命中不叠加动作（由 RuleSessionState 冷却保证）。
 */
object RulePriority {

    /** 条件具体度：非空匹配字段越多越具体（textEquals 权重高于 textContains）。 */
    fun specificity(rule: UiRule): Int {
        val m = rule.match
        var score = 0
        if (m.viewId != null) score += 4
        if (m.className != null) score += 2
        if (m.textEquals != null) score += 2
        if (m.textContains != null) score += 1
        if (m.clickable != null) score += 1
        if (m.parentViewId != null) score += 2
        return score
    }

    /**
     * @return 排序后的规则：最应执行的在前。稳定的确定顺序（可重放测试）。
     */
    fun sortedForExecution(rules: List<UiRule>): List<UiRule> =
        rules.sortedWith(
            compareBy<UiRule> { it.action.type != UiRule.RuleAction.ActionType.RECORD_ONLY } // RECORD_ONLY(false=0)在前
                .thenByDescending { specificity(it) }
                .thenBy { it.id }
                .thenBy { it.version }
        )
}
