package top.hnwen17.guard.core.rules

/**
 * 快照组装器（core 纯函数，可单测）。
 *
 * 采集端按**层序（BFS）**读取无障碍树并逐节点记录 [Rec]，本组装器把记录
 * 自底向上还原成 [SnapshotNode] 树（父索引恒小于子索引，逆序遍历即先子后父）。
 *
 * 为什么是 BFS：广告 SDK 素材子树（WebView/动效模板）巨大，DFS 截断会先耗尽
 * 预算，把同层浅层的跳过按钮/「广告」标签整体挤出快照——引擎看不见关键控件，
 * 匹配与观察双双落空（用户实测：腾讯视频互动开屏零记录零跳过）。
 * BFS 保证深度浅的控件优先入选；binder 读取次数与深度上限不变。
 */
object SnapshotAssembler {

    /** 采集端单节点记录（纯数据，不含平台类型）。 */
    data class Rec(
        val depth: Int,
        val parent: Int, // -1 = 根
        val viewId: String?,
        val className: String?,
        val text: String?,
        val desc: String?,
        val clickable: Boolean,
        val isPassword: Boolean,
        val left: Int, val top: Int, val right: Int, val bottom: Int
    )

    /**
     * 按记录顺序（BFS 序）组装快照，返回与 [recs] 同序的节点数组（索引 0 = 根）。
     * recs 为空返回空列表；BFS 序保证父索引 < 子索引，逆序遍历即先子后父。
     * 未被读取的子树不存在于结果中（截断语义与旧实现一致，只是选择顺序不同）。
     */
    fun assembleAll(recs: List<Rec>): List<SnapshotNode?> {
        if (recs.isEmpty()) return emptyList()
        val childIdxOf = HashMap<Int, MutableList<Int>>(recs.size)
        for (i in 1 until recs.size) {
            childIdxOf.getOrPut(recs[i].parent) { ArrayList() }.add(i)
        }
        val built = arrayOfNulls<SnapshotNode>(recs.size)
        for (i in recs.indices.reversed()) {
            val r = recs[i]
            val kids = childIdxOf[i].orEmpty().mapNotNull { built[it] }
            built[i] = SnapshotNode(
                viewId = r.viewId,
                className = r.className,
                text = r.text,
                clickable = r.clickable,
                children = kids,
                isPassword = r.isPassword,
                boundsInScreen = SnapshotNode.Bounds(r.left, r.top, r.right, r.bottom),
                desc = r.desc
            )
        }
        return built.toList()
    }

    /** 便捷入口：只取根快照。 */
    fun assemble(recs: List<Rec>): SnapshotNode? = assembleAll(recs).firstOrNull()
}
