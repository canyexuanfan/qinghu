package top.hnwen17.guard.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import top.hnwen17.guard.core.rules.RuleLimits
import top.hnwen17.guard.core.policy.SafetyExclusions
import top.hnwen17.guard.core.rules.SnapshotNode

/**
 * 节点快照读取器（QH-P05-05）。
 *
 * 在**工作线程**把 AccessibilityNodeInfo 树压缩为有界 [SnapshotNode]：
 * - 节点数 ≤ [RuleLimits.MAX_NODES_PER_WINDOW]、深度 ≤ [RuleLimits.MAX_NESTING_DEPTH]；
 * - 文本截断到小上限（快照只服务匹配，不承载内容）；
 * - 密码节点：isPassword → 不复制文本、整节点标记，供 SafetyExclusions 排除；
 * - AccessibilityNodeInfo 生命周期：读取字段后立即 recycle 语义（API 33+ 自动管理，
 *   低版本显式 recycle，且**不缓存**任何节点实例跨事件使用）。
 * - null/过期/跨窗口节点：全部安全返回部分结果，不抛出。
 */
object NodeSnapshotReader {

    private const val MAX_TEXT = 64

    /**
     * 后验检查（QH-P08）：目标 viewId（短名比较）是否仍存在于当前窗口。
     * 有界遍历；root 不可用时返回 null（调用方保持 EXECUTED 不冒充 VERIFIED）。
     */
    fun containsViewId(root: AccessibilityNodeInfo?, shortId: String): Boolean? {
        root ?: return null
        val budget = IntArray(1) { RuleLimits.MAX_NODES_PER_WINDOW }
        return contains(root, shortId, 0, budget)
    }

    /**
     * QH-P18 通用后验：检查「标记」（命中节点的 text/desc/viewId 短名）是否仍存在于窗口。
     * 有界遍历；root 不可用时返回 null（无法确认）。
     */
    fun containsMarker(root: AccessibilityNodeInfo?, marker: String): Boolean? {
        root ?: return null
        val budget = IntArray(1) { RuleLimits.MAX_NODES_PER_WINDOW }
        return markerWalk(root, marker, 0, budget)
    }

    private fun markerWalk(node: AccessibilityNodeInfo, marker: String, depth: Int, budget: IntArray): Boolean? {
        if (depth > RuleLimits.MAX_NESTING_DEPTH) return false
        if (budget[0] <= 0) return false
        budget[0]--
        try {
            if (node.text?.toString() == marker) return true
            if (node.contentDescription?.toString() == marker) return true
            if (node.viewIdResourceName?.substringAfterLast('/') == marker) return true
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = markerWalk(child, marker, depth + 1, budget)
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    try { child.recycle() } catch (_: Exception) { }
                }
                if (found == true) return true
            }
            return false
        } catch (_: Exception) {
            return null // 节点失效：调用方按"无法确认"处理
        }
    }

    private fun contains(node: AccessibilityNodeInfo, shortId: String, depth: Int, budget: IntArray): Boolean? {
        if (depth > RuleLimits.MAX_NESTING_DEPTH) return false
        if (budget[0] <= 0) return false
        budget[0]--
        try {
            if (node.viewIdResourceName?.substringAfterLast('/') == shortId) return true
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = contains(child, shortId, depth + 1, budget)
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    try { child.recycle() } catch (_: Exception) { }
                }
                if (found == true) return true
            }
            return false
        } catch (_: Exception) {
            return null // 节点失效：调用方按"无法确认"处理
        }
    }

    /** @return 整窗快照；root 不可用（窗口已切换/无权限）时返回 null。 */
    fun snapshot(root: AccessibilityNodeInfo?): SnapshotNode? {
        root ?: return null
        val budget = IntArray(1) { RuleLimits.MAX_NODES_PER_WINDOW }
        return readNode(root, depth = 0, budget = budget)
    }

    /**
     * 带活节点引用的快照（QH-P08）：快照节点 → 对应 AccessibilityNodeInfo，
     * 供点击执行器在同一工作线程内立即 performAction。**用完必须 [LiveSnapshot.recycleAll]**，
     * 绝不缓存节点跨事件使用。
     */
    fun snapshotLive(root: AccessibilityNodeInfo?): LiveSnapshot? {
        root ?: return null
        val budget = IntArray(1) { RuleLimits.MAX_NODES_PER_WINDOW }
        val live = mutableListOf<AccessibilityNodeInfo>()
        val map = HashMap<SnapshotNode, AccessibilityNodeInfo>()
        val snapshot = readNode(root, depth = 0, budget = budget, live = live, map = map) ?: return null
        return LiveSnapshot(snapshot, live, map)
    }

    class LiveSnapshot(
        val rootSnapshot: SnapshotNode,
        private val live: List<AccessibilityNodeInfo>,
        private val map: Map<SnapshotNode, AccessibilityNodeInfo>
    ) {
        fun nodeFor(snapshot: SnapshotNode): AccessibilityNodeInfo? = map[snapshot]

        /** API<33 释放全部节点；API 33+ 为空操作。 */
        fun recycleAll() {
            if (android.os.Build.VERSION.SDK_INT >= 33) return
            for (n in live) try { n.recycle() } catch (_: Exception) { }
        }
    }

    private fun readNode(node: AccessibilityNodeInfo?, depth: Int, budget: IntArray): SnapshotNode? =
        readNode(node, depth, budget, null, null)

    private fun readNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        budget: IntArray,
        live: MutableList<AccessibilityNodeInfo>?,
        map: HashMap<SnapshotNode, AccessibilityNodeInfo>?
    ): SnapshotNode? {
        node ?: return null
        if (depth > RuleLimits.MAX_NESTING_DEPTH) return null
        if (budget[0] <= 0) return null
        budget[0]--
        try {
            val isPassword = node.isPassword
            val rawText = node.text?.toString()
            val text = when {
                isPassword -> null // 密码内容绝不复制
                rawText != null && rawText.length > MAX_TEXT -> rawText.take(MAX_TEXT)
                else -> rawText
            }
            val children = mutableListOf<SnapshotNode>()
            if (budget[0] > 0 && depth < RuleLimits.MAX_NESTING_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    val snapshotChild = readNode(child, depth + 1, budget, live, map)
                    if (snapshotChild != null && live != null && map != null) {
                        live.add(child); map[snapshotChild] = child // 活引用模式：由 LiveSnapshot.recycleAll 统一释放
                    } else if (android.os.Build.VERSION.SDK_INT < 33) {
                        try { child.recycle() } catch (_: Exception) { /* API 差异安全网 */ }
                    }
                    snapshotChild?.let(children::add)
                    if (budget[0] <= 0) break
                }
            }
            val b = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            val rawDesc = node.contentDescription?.toString()
            val desc = when {
                isPassword -> null // 与文本同口径：密码节点不采集 desc
                rawDesc != null && rawDesc.length > MAX_TEXT -> rawDesc.take(MAX_TEXT)
                else -> rawDesc
            }
            return SnapshotNode(
                viewId = node.viewIdResourceName,
                className = node.className?.toString(),
                text = text,
                clickable = node.isClickable,
                children = children,
                isPassword = isPassword,
                boundsInScreen = SnapshotNode.Bounds(b.left, b.top, b.right, b.bottom),
                desc = desc
            )
        } catch (_: Exception) {
            // 节点在读取中途失效（窗口切换/进程死亡）：返回 null 让上层回退，绝不抛出
            return null
        }
    }

    /**
     * 快照安全门（QH-P07-07 的平台接线）：整窗敏感（含密码节点）时
     * 主动动作一律否决；本读取器不缓存任何窗口树。
     */
    fun windowSensitive(snapshot: SnapshotNode): Boolean =
        SafetyExclusions.windowHasSensitiveContext(snapshot)
}
