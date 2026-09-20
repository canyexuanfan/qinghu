package top.hnwen17.guard.core.rules

/**
 * 按包规则索引（QH-P07-04）。
 *
 * 结构：package → (按版本区间归档的候选规则)。查询时**只看目标包**，
 * 无候选时不遍历全表（O(1) 哈希查找）。预算：≤10000 条索引 + ≤4MiB 缓存。
 * 线程模型：构建一次（规则加载在 IO 线程），查询只读（不可变结构）。
 */
class RuleIndex private constructor(
    private val byPackage: Map<String, List<IndexedRule>>,
    private val wildcardRules: List<IndexedRule>,
    val ruleCount: Int,
    private val approxBytes: Int
) {

    /** 索引条目：保留原始规则 + 归档时间，供缓存淘汰与诊断。 */
    data class IndexedRule(val rule: UiRule, val packId: String)

    /**
     * 查询指定包在给定版本下的候选规则。
     * target.package = "*" 的规则为通用规则（对任意包生效，如内置"跳过"文案规则）。
     */
    fun candidatesFor(
        packageName: String,
        versionCode: Long,
        /** QH-阶段1：已确认的 Activity 全类名；null=无法确认（带 activityIds 的规则被裁掉，fail-closed）。 */
        activityId: String? = null,
        activityConfirmed: Boolean = false
    ): List<IndexedRule> {
        fun versionOk(r: IndexedRule) = versionCode in r.rule.target.minVersionCode..r.rule.target.maxVersionCode
        fun activityOk(r: IndexedRule): Boolean {
            val ids = r.rule.match.activityIds ?: return true
            if (!activityConfirmed || activityId == null) return false // fail-closed
            if (activityId !in ids) return false
            return true
        }
        fun excludeOk(r: IndexedRule): Boolean {
            val ex = r.rule.match.excludeActivityIds ?: return true
            if (!activityConfirmed || activityId == null) return false // fail-closed
            return activityId !in ex
        }
        val specific = byPackage[packageName].orEmpty().filter { versionOk(it) && activityOk(it) && excludeOk(it) }
        val generic = wildcardRules.filter { versionOk(it) && activityOk(it) && excludeOk(it) }
        return specific + generic
    }

    fun hasPackage(packageName: String): Boolean = byPackage.containsKey(packageName)

    /** 近似内存占用（字节）：超出缓存预算的信号，供加载层淘汰整包。 */
    fun approxMemoryBytes(): Int = approxBytes

    fun overCacheBudget(budgetBytes: Int): Boolean = approxBytes > budgetBytes

    companion object {
        const val CACHE_BUDGET_BYTES = 4 * 1024 * 1024
        /** 通用规则通配包名（对任意包生效）。 */
        const val WILDCARD_PACKAGE = "*"

        /** 每条规则近似字节数：viewId/类名/文本字段的 UTF-8 长度 + 固定头。 */
        fun build(packs: List<RulePack>): RuleIndex {
            var count = 0
            var bytes = 0
            val map = HashMap<String, MutableList<IndexedRule>>()
            val wild = mutableListOf<IndexedRule>()
            for (pack in packs) {
                for (rule in pack.rules) {
                    val m = rule.match
                    val approx = fixedHeaderBytes +
                        m.viewId.utf8() + m.className.utf8() + m.textEquals.utf8() +
                        m.textContains.utf8() + m.parentViewId.utf8() +
                        rule.target.packageName.utf8()
                    if (rule.target.packageName == WILDCARD_PACKAGE) wild.add(IndexedRule(rule, pack.id))
                    else map.getOrPut(rule.target.packageName) { mutableListOf() }
                        .add(IndexedRule(rule, pack.id))
                    count++
                    bytes += approx
                    if (count > RuleLimits.MAX_RULES) {
                        throw IllegalStateException("rule index exceeds ${RuleLimits.MAX_RULES}")
                    }
                }
            }
            return RuleIndex(map, wild.toList(), count, bytes)
        }

        private fun String?.utf8(): Int = (this?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        private const val fixedHeaderBytes = 96
    }
}
