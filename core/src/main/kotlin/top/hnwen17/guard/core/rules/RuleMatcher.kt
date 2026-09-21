package top.hnwen17.guard.core.rules

import top.hnwen17.guard.core.Capability

/**
 * 节点快照与便宜字段匹配（QH-P07-05/06）。
 *
 * core 不接触 Android AccessibilityNodeInfo：app 采集端在**工作线程**上
 * 提取有界快照（[SnapshotNode]，≤256 节点），core 只消费快照。
 * 匹配顺序：先包名/版本/viewId/类名/clickable（字符串/布尔比较，便宜），
 * 再做有限文本比较（首版不做任意正则）。短路失败，不读无用节点。
 */
data class SnapshotNode(
    val viewId: String?,
    val className: String?,
    val text: String?,
    val clickable: Boolean,
    val children: List<SnapshotNode>,
    /** 采集端从 AccessibilityNodeInfo.isPassword 填充；密码节点永不进入匹配。 */
    val isPassword: Boolean = false,
    /** 屏幕坐标边界（QH-P09 TouchShield 定位用；未知为 null）。 */
    val boundsInScreen: Bounds? = null,
    /** QH-P18：无障碍 contentDescription（部分 SDK 的跳过控件只有 desc 没有文本）。 */
    val desc: String? = null
) {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    /** 快照统计：供上限断言与诊断。 */
    fun nodeCount(): Int = 1 + children.sumOf { it.nodeCount() }

    fun depth(): Int = if (children.isEmpty()) 1 else 1 + children.maxOf { it.depth() }

    companion object {
        /** 采集端预算（与 RuleLimits.MAX_NODES_PER_WINDOW 一致）。 */
        fun isWithinBudget(root: SnapshotNode): Boolean =
            root.nodeCount() <= RuleLimits.MAX_NODES_PER_WINDOW && root.depth() <= RuleLimits.MAX_NESTING_DEPTH
    }
}

/** 匹配输入：目标窗口快照 + 每个可交互节点的父链 viewId（用于 parentViewId 条件）。 */
data class WindowSnapshot(
    val packageName: String,
    val versionCode: Long,
    val root: SnapshotNode,
    /** node → 父节点 viewId（近父）；采集端构建，避免匹配器回溯。 */
    val parentViewIds: Map<SnapshotNode, String?> = emptyMap(),
    /** QH-阶段1：已确认的 Activity 全类名（仅 TYPE_WINDOW_STATE_CHANGED 事件填充）。 */
    val activityId: String? = null,
    /** 上述 activityId 是否可靠（true=来自窗口状态变更事件；false=无法确认，activityIds 规则 fail-closed）。 */
    val activityConfirmed: Boolean = false
)

object RuleMatcher {

    /** 匹配结果：命中的规则 + 建议能力（供仲裁与记录）。 */
    data class Match(val rule: UiRule, val node: SnapshotNode, val capability: Capability)

    /**
     * 在候选（已按包/版本过滤）中找命中规则。
     * - 便宜字段短路：viewId 不等直接失败，不读 text；
     * - 文本比较上限：textContains 只在 text 长度 ≤ 512B 时执行（超限视为不匹配，防大文本拖垮）；
     * - 返回最多 [RuleLimits.MAX_CANDIDATES] 条，防规则洪泛。
     * QH-P18：窗口级上下文（文本/id 聚合 blob）每次调用预计算一次，供结构规则闸门共享。
     */
    fun findMatches(candidates: List<RuleIndex.IndexedRule>, snapshot: WindowSnapshot): List<Match> {
        val out = mutableListOf<Match>()
        for (indexed in candidates) {
            if (out.size >= RuleLimits.MAX_CANDIDATES) break
            val rule = indexed.rule
            val ctx = WindowCtx.of(snapshot, rule.match)
            // QH-阶段1：Activity 白/黑名单（fail-closed——无法确认 Activity 时该规则不参与）
            if (rule.match.activityIds != null || rule.match.excludeActivityIds != null) {
                if (!snapshot.activityConfirmed || snapshot.activityId == null) continue
                if (rule.match.activityIds != null && rule.match.activityIds.none { it == snapshot.activityId }) continue
                if (rule.match.excludeActivityIds != null && rule.match.excludeActivityIds.any { it == snapshot.activityId }) continue
            }
            // QH-P18 窗口级闸门：规则级判一次（李跳跳/GKD「出现广告容器才点关闭」语义）
            if (rule.match.windowTextContainsAny != null &&
                ctx.textBlob?.let { b -> rule.match.windowTextContainsAny!!.any { b.contains(it.lowercase()) } } != true) continue
            if (rule.match.windowTextEqualsAny != null &&
                ctx.fullTexts?.let { ts -> rule.match.windowTextEqualsAny!!.any { v -> ts.contains(v.trim().lowercase()) } } != true) continue
            if (rule.match.windowViewIdContainsAny != null &&
                ctx.viewIdBlob?.let { b -> rule.match.windowViewIdContainsAny!!.any { b.contains(it.lowercase()) } } != true) continue
            val node = findNode(snapshot.root, rule.match, 0, emptyList(), ctx) ?: continue
            val capability = when (rule.action.type) {
                UiRule.RuleAction.ActionType.CLICK_VERIFIED_NODE -> Capability.CLEANER
                UiRule.RuleAction.ActionType.RECORD_ONLY -> Capability.CLEANER
            }
            out.add(Match(rule, node, capability))
        }
        return out
    }

    /** 窗口级聚合上下文：仅当规则需要时才构建（惰性，单事件内共享）。 */
    private class WindowCtx private constructor(
        val textBlob: String?,
        val fullTexts: Set<String>?,
        val viewIdBlob: String?
    ) {
        companion object {
            fun of(snapshot: WindowSnapshot, cond: UiRule.MatchCondition): WindowCtx {
                val needText = cond.windowTextContainsAny != null
                val needEquals = cond.windowTextEqualsAny != null
                val needIds = cond.windowViewIdContainsAny != null
                if (!needText && !needEquals && !needIds) return WindowCtx(null, null, null)
                val texts = if (needText) StringBuilder() else null
                val equalsSet = if (needEquals) HashSet<String>() else null
                val ids = if (needIds) StringBuilder() else null
                walk(snapshot.root, texts, equalsSet, ids)
                return WindowCtx(texts?.toString(), equalsSet, ids?.toString())
            }

            private fun walk(n: SnapshotNode, texts: StringBuilder?, equalsSet: MutableSet<String>?, ids: StringBuilder?) {
                if (texts != null) {
                    n.text?.takeIf { it.isNotBlank() }?.let { texts.append(it.lowercase()).append('\n') }
                    n.desc?.takeIf { it.isNotBlank() }?.let { texts.append(it.lowercase()).append('\n') }
                }
                if (equalsSet != null) {
                    n.text?.trim()?.takeIf { it.isNotEmpty() }?.let { equalsSet.add(it.lowercase()) }
                    n.desc?.trim()?.takeIf { it.isNotEmpty() }?.let { equalsSet.add(it.lowercase()) }
                }
                if (ids != null) {
                    n.viewId?.let { ids.append(it.substringAfterLast('/').lowercase()).append('\n') }
                }
                for (c in n.children) walk(c, texts, equalsSet, ids)
            }
        }
    }

    /** 深度受限先序查找第一个满足条件的节点（携带祖先链供 parentViewId/兄弟轴/广告容器证据判定）。 */
    private fun findNode(
        node: SnapshotNode, cond: UiRule.MatchCondition, depth: Int,
        ancestors: List<SnapshotNode>, ctx: WindowCtx, childIndex: Int = 0, siblingCount: Int = 1
    ): SnapshotNode? {
        if (depth > cond.maxDepth) return null
        if (matches(node, cond, ancestors, ctx, childIndex, siblingCount)) return node
        val next = ancestors + node
        for ((i, child) in node.children.withIndex()) {
            findNode(child, cond, depth + 1, next, ctx, i, node.children.size)?.let { return it }
        }
        return null
    }

    /** QH-P18：兄弟子树文本扫描（小写包含；有界，快照本身受预算约束）。 */
    private fun siblingSubtreeHasText(n: SnapshotNode, needles: List<String>): Boolean {
        n.text?.let { t -> val l = t.lowercase(); if (needles.any { l.contains(it) }) return true }
        n.desc?.let { d -> val l = d.lowercase(); if (needles.any { l.contains(it) }) return true }
        for (c in n.children) if (siblingSubtreeHasText(c, needles)) return true
        return false
    }

    private fun matches(
        node: SnapshotNode, cond: UiRule.MatchCondition, ancestors: List<SnapshotNode> = emptyList(),
        ctx: WindowCtx? = null, childIndex: Int = 0, siblingCount: Int = 1
    ): Boolean {
        val parent = ancestors.lastOrNull()
        // 便宜字段先判（短路失败）；viewId 统一按资源短名比较（Android 上报为 pkg:id/name）
        if (cond.viewId != null && node.viewId?.substringAfterLast('/') != cond.viewId) return false
        // QH-P18：viewId 短名包含匹配（大小写不敏感；GKD 通用规则 vid~=.*skip.* 的等价落地）
        if (cond.viewIdContains != null && node.viewId?.substringAfterLast('/')?.lowercase()?.contains(cond.viewIdContains.lowercase()) != true) return false
        if (cond.className != null && node.className != cond.className) return false
        // QH-P18 通用性：SDK 类名尾缀（如 SkipView 命中穿山甲/优量汇等各家的 *SplashSkipView）
        if (cond.classNameSuffix != null && node.className?.endsWith(cond.classNameSuffix) != true) return false
        if (cond.clickable != null && node.clickable != cond.clickable) return false
        // QH-P18 结构类：尺寸上限（px，屏幕绝对坐标）；无边界信息时不放行
        if (cond.maxWidth != null || cond.maxHeight != null) {
            val b = node.boundsInScreen ?: return false
            if (cond.maxWidth != null && b.right - b.left > cond.maxWidth) return false
            if (cond.maxHeight != null && b.bottom - b.top > cond.maxHeight) return false
        }
        // QH-P18 结构类：纯图标控件（text 空/空白）
        if (cond.textEmpty == true && !node.text.isNullOrBlank()) return false
        if (cond.textEmpty == false && node.text.isNullOrBlank()) return false
        // QH-P18 兄弟轴/子节点数（GKD childCount、lastChild 与 -(1,2) 兄弟轴的等价子集）
        if (cond.childCountMax != null && node.children.size > cond.childCountMax) return false
        if (cond.childCountEquals != null && node.children.size != cond.childCountEquals) return false
        if (cond.lastChild != null) {
            val isLast = siblingCount > 0 && childIndex == siblingCount - 1
            if (isLast != cond.lastChild) return false
        }
        if (cond.siblingTextContainsAny != null) {
            val p = parent ?: return false
            val needles = cond.siblingTextContainsAny.map { it.lowercase() }
            var found = false
            for ((i, sib) in p.children.withIndex()) {
                if (i == childIndex) continue
                if (siblingSubtreeHasText(sib, needles)) { found = true; break }
            }
            if (!found) return false
        }
        // 父约束：仅近父一层，父 viewId 按资源短名比较
        if (cond.parentViewId != null) {
            if (parent == null) return false
            if (parent.viewId?.substringAfterLast('/') != cond.parentViewId) return false
        }
        // 节点级广告容器证据：祖先链上任一 id 短名含标记才放行。
        // 窗口级文本证据无法区分同窗正/负例（教程「跳过」与广告「跳过」同窗），祖先容器才是结构信号。
        if (cond.ancestorViewIdContainsAny != null) {
            val needles = cond.ancestorViewIdContainsAny.map { it.lowercase() }
            val hit = ancestors.any { a ->
                a.viewId?.let { id -> val sh = id.substringAfterLast('/').lowercase(); needles.any { sh.contains(it) } } == true
            }
            if (!hit) return false
        }
        // 文本比较：空值语义确定——规则要求文本而节点无文本 → 不匹配
        if (cond.textEquals != null && node.text != cond.textEquals) return false
        if (cond.textContains != null) {
            val text = node.text ?: return false
            if (text.toByteArray(Charsets.UTF_8).size > RuleLimits.MAX_STRING_BYTES) return false
            if (!text.contains(cond.textContains)) return false
        }
        // QH-P18：contentDescription 匹配（部分 SDK 跳过控件只有 desc 没有文本）
        if (cond.descContains != null) {
            val desc = node.desc ?: return false
            if (desc.toByteArray(Charsets.UTF_8).size > RuleLimits.MAX_STRING_BYTES) return false
            if (!desc.contains(cond.descContains)) return false
        }
        return true
    }

    /**
     * 页面约束检查（QH-P07-07 的 core 部分）：
     * requiredViewIds 全部存在且 mustNotHave 全部不存在，规则才可执行。
     * 敏感上下文排除由 app 层在快照提取时先行过滤（密码框等不进快照），
     * core 侧对 mustNotHave 提供防御性二次检查。
     */
    fun pageAllows(constraint: UiRule.PageConstraint?, snapshot: WindowSnapshot): Boolean {
        constraint ?: return true
        val allIds = collectViewIds(snapshot.root, HashSet())
        for (required in constraint.requiredViewIds) {
            if (allIds.none { it.substringAfterLast('/') == required }) return false
        }
        for (forbidden in constraint.mustNotHaveViewIds) {
            if (allIds.any { it.substringAfterLast('/') == forbidden }) return false
        }
        return true
    }

    private fun collectViewIds(node: SnapshotNode, into: MutableSet<String>): MutableSet<String> {
        node.viewId?.let { into.add(it) }
        for (child in node.children) collectViewIds(child, into)
        return into
    }
}

/**
 * 有界节点关系查询（QH-P07-06）：父子/兄弟查找设深度与节点上限，
 * 不做无限递归；快照本身有界（采集端保证），查找再受 maxDepth 约束。
 */
object NodeRelations {
    const val MAX_SEARCH_DEPTH = 8

    /** 找满足条件的"父节点"（仅近父，一层）；parentViewIds 由采集端提供。 */
    fun parentMatches(node: SnapshotNode, parentViewId: String?, parents: Map<SnapshotNode, SnapshotNode>): Boolean {
        parentViewId ?: return true
        val parent = parents[node] ?: return false
        return parent.viewId == parentViewId
    }

    /** 兄弟查找：同父下的其他节点（上限 [MAX_SEARCH_DEPTH] 层遍历已由快照有界保证）。 */
    fun siblingsOf(node: SnapshotNode, parents: Map<SnapshotNode, SnapshotNode>): List<SnapshotNode> {
        val parent = parents[node] ?: return emptyList()
        return parent.children.filter { it !== node }
    }
}
