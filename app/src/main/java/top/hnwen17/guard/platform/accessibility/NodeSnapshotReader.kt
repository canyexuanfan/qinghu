package top.hnwen17.guard.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import top.hnwen17.guard.core.rules.RuleLimits
import top.hnwen17.guard.core.policy.SafetyExclusions
import top.hnwen17.guard.core.rules.SnapshotAssembler
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
        return buildBfs(root, live = null)
    }

    /**
     * 带活节点引用的快照（QH-P08）：快照节点 → 对应 AccessibilityNodeInfo，
     * 供点击执行器在同一工作线程内立即 performAction。**用完必须 [LiveSnapshot.recycleAll]**，
     * 绝不缓存节点跨事件使用。
     */
    fun snapshotLive(root: AccessibilityNodeInfo?): LiveSnapshot? {
        root ?: return null
        val live = mutableListOf<AccessibilityNodeInfo>()
        val map = HashMap<SnapshotNode, AccessibilityNodeInfo>()
        val snapshot = buildBfs(root, live = live, map = map) ?: return null
        return LiveSnapshot(snapshot, live, map)
    }

    /**
     * 层序（BFS）有界读取：先读浅层、后读深层，预算内节点数/binder 往返不变。
     * 根因背景见 [SnapshotAssembler]：DFS 截断会让巨大素材子树挤掉同层跳过按钮。
     */
    private fun buildBfs(
        root: AccessibilityNodeInfo,
        live: MutableList<AccessibilityNodeInfo>?,
        map: HashMap<SnapshotNode, AccessibilityNodeInfo>? = null
    ): SnapshotNode? {
        val liveMode = live != null
        val recs = ArrayList<SnapshotAssembler.Rec>(64)
        val infos = ArrayList<AccessibilityNodeInfo>(64) // 与 recs 同序；live 模式由 LiveSnapshot 释放
        val queue = ArrayDeque<Int>()
        try {
            recs.add(readRec(root, depth = 0, parent = -1))
        } catch (_: Exception) {
            return null // 根节点立即失效：整窗放弃（与旧实现一致）
        }
        infos.add(root) // 根由调用方管理，不入 live/map
        queue.add(0)
        var budget = RuleLimits.MAX_NODES_PER_WINDOW - 1
        while (queue.isNotEmpty()) {
            if (budget <= 0) break
            val parentIdx = queue.removeFirst()
            val parent = recs[parentIdx]
            if (parent.depth >= RuleLimits.MAX_NESTING_DEPTH) continue // 子节点将超深
            val parentInfo = infos[parentIdx]
            val childCount = try { parentInfo.childCount } catch (_: Exception) { 0 }
            for (i in 0 until childCount) {
                if (budget <= 0) break
                val child = try { parentInfo.getChild(i) } catch (_: Exception) { null } ?: continue
                val rec = try {
                    readRec(child, parent.depth + 1, parentIdx)
                } catch (_: Exception) {
                    if (android.os.Build.VERSION.SDK_INT < 33) try { child.recycle() } catch (_: Exception) { }
                    continue
                }
                recs.add(rec); infos.add(child); budget--
                queue.add(recs.size - 1)
                if (liveMode) live!!.add(child)
            }
            if (!liveMode && parentIdx != 0) {
                // 非 live 模式：节点属性已尽其用（子树展开完成），按旧实现口径立即释放
                if (android.os.Build.VERSION.SDK_INT < 33) try { parentInfo.recycle() } catch (_: Exception) { }
            }
        }
        if (!liveMode) {
            // 预算耗尽未展开的队列残留：同样立即释放
            for (idx in queue) {
                if (idx != 0 && android.os.Build.VERSION.SDK_INT < 33) {
                    try { infos[idx].recycle() } catch (_: Exception) { }
                }
            }
        }
        val built = SnapshotAssembler.assembleAll(recs)
        val snapshot = built.firstOrNull() ?: return null
        if (liveMode && map != null) {
            for (i in 1 until recs.size) built[i]?.let { map[it] = infos[i] }
        }
        return snapshot
    }

    /** 单节点属性读取（一次 binder 往返；字段与脱敏口径与旧实现一致）。 */
    private fun readRec(node: AccessibilityNodeInfo, depth: Int, parent: Int): SnapshotAssembler.Rec {
        val isPassword = node.isPassword
        val rawText = node.text?.toString()
        val text = when {
            isPassword -> null // 密码内容绝不复制
            rawText != null && rawText.length > MAX_TEXT -> rawText.take(MAX_TEXT)
            else -> rawText
        }
        val b = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        val rawDesc = node.contentDescription?.toString()
        val desc = when {
            isPassword -> null // 与文本同口径：密码节点不采集 desc
            rawDesc != null && rawDesc.length > MAX_TEXT -> rawDesc.take(MAX_TEXT)
            else -> rawDesc
        }
        return SnapshotAssembler.Rec(
            depth = depth, parent = parent,
            viewId = node.viewIdResourceName, className = node.className?.toString(),
            text = text, desc = desc, clickable = node.isClickable, isPassword = isPassword,
            left = b.left, top = b.top, right = b.right, bottom = b.bottom
        )
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

    /**
     * 快照安全门（QH-P07-07 的平台接线）：整窗敏感（含密码节点）时
     * 主动动作一律否决；本读取器不缓存任何窗口树。
     */
    fun windowSensitive(snapshot: SnapshotNode): Boolean =
        SafetyExclusions.windowHasSensitiveContext(snapshot)
}
