package top.hnwen17.guard.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照组装器（BFS 截断修复）：巨素材子树不得挤掉同层浅层控件。
 * 用户实测背景：腾讯视频互动开屏的跳过按钮/「互动广告」标签被 DFS 截断挤出快照，
 * 引擎零匹配零记录；BFS 后浅层控件必入选。
 */
class SnapshotAssemblerTest {

    private fun rec(depth: Int, parent: Int, text: String? = null, viewId: String? = null) =
        SnapshotAssembler.Rec(
            depth = depth, parent = parent, viewId = viewId, className = "android.widget.TextView",
            text = text, desc = null, clickable = false, isPassword = false,
            left = 0, top = 0, right = 100, bottom = 40
        )

    @Test
    fun `BFS 序组装：巨素材兄弟不挤掉浅层跳过按钮`() {
        // root(0) → container(1) → [webview(2) + 其巨量子树, skip 按钮(3), 广告标签(4)]
        // DFS 会在 webview 子树里耗尽预算；BFS 保证 skip/标签先入选
        val recs = listOf(
            rec(0, -1),
            rec(1, 0),
            rec(2, 1, viewId = "webview_ad_material"),
            rec(2, 1, text = "跳过", viewId = "skip_view"),
            rec(2, 1, text = "互动广告")
        )
        val root = SnapshotAssembler.assemble(recs)
        assertNotNull(root)
        val container = root!!.children.single()
        // BFS 语义：webview 与 skip/标签同层时，三者全部入选（DFS 会在 webview 先展开耗预算）
        assertEquals(3, container.children.size)
        val skip = container.children.first { it.viewId == "skip_view" }
        assertEquals("跳过", skip.text)
        // 「互动广告」标签同层可见（观察启发式依赖它）
        assertTrue(container.children.any { it.text == "互动广告" })
        assertEquals(5, root.nodeCount())
    }

    @Test
    fun `兄弟顺序与读取顺序一致`() {
        val recs = listOf(
            rec(0, -1, text = "root"),
            rec(1, 0, text = "a"),
            rec(1, 0, text = "b"),
            rec(1, 0, text = "c")
        )
        val root = SnapshotAssembler.assemble(recs)!!
        assertEquals(listOf("a", "b", "c"), root.children.map { it.text })
    }

    @Test
    fun `预算截断语义：未入选子树不存在（与旧实现一致）`() {
        // 前序：root → a(含子树) ；b 因预算从未被记录 → 不出现在快照
        val recs = listOf(
            rec(0, -1),
            rec(1, 0, text = "a"),
            rec(2, 1, text = "a-child")
        )
        val root = SnapshotAssembler.assemble(recs)!!
        assertEquals(listOf("a"), root.children.map { it.text })
        assertEquals(3, root.nodeCount())
    }

    @Test
    fun `空记录与深度链`() {
        assertNull(SnapshotAssembler.assemble(emptyList()))
        // 5 层深链还原
        val recs = (0 until 5).map { d -> rec(d, if (d == 0) -1 else d - 1, text = "d$d") }
        val root = SnapshotAssembler.assemble(recs)!!
        assertEquals(5, root.depth())
        var n: SnapshotNode = root
        for (d in 1 until 5) {
            n = n.children.single()
            assertEquals("d$d", n.text)
        }
    }

    @Test
    fun `逐索引结果与活引用映射同序`() {
        val recs = listOf(rec(0, -1), rec(1, 0, text = "x"), rec(1, 0, text = "y"))
        val all = SnapshotAssembler.assembleAll(recs)
        assertEquals(recs.size, all.size)
        assertEquals(recs[1].text, all[1]!!.text)
        assertEquals(recs[2].text, all[2]!!.text)
        assertEquals(all[0], all[0]) // 根在索引 0
        assertNull(SnapshotAssembler.assembleAll(emptyList()).firstOrNull())
    }
}
