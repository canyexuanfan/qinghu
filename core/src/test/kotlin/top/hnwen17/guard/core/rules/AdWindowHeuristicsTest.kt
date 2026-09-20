package top.hnwen17.guard.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QH-P18 观察日志启发式：疑似广告窗口判定与有界采样。 */
class AdWindowHeuristicsTest {

    private fun n(
        viewId: String? = null, className: String? = null, text: String? = null,
        clickable: Boolean = false, children: List<SnapshotNode> = emptyList()
    ) = SnapshotNode(viewId, className, text, clickable, children)

    @Test
    fun `类名命中广告标记即疑似`() {
        assertTrue(AdWindowHeuristics.isSuspectAdWindow("com.bytedance.sdk.openadsdk.activity.SplashActivity", n()))
        assertTrue(AdWindowHeuristics.isSuspectAdWindow("com.kwad.components.ad.SplashAdActivity", n()))
        assertFalse(AdWindowHeuristics.isSuspectAdWindow("com.example.app.MainActivity", n()))
        assertFalse(AdWindowHeuristics.isSuspectAdWindow(null, n()))
    }

    @Test
    fun `含跳过文本即疑似`() {
        val tree = n(children = listOf(n(text = "跳过 3"), n(text = "精彩内容")))
        assertTrue(AdWindowHeuristics.isSuspectAdWindow("com.example.app.MainActivity", tree))
        val none = n(children = listOf(n(text = "欢迎回来")))
        assertFalse(AdWindowHeuristics.isSuspectAdWindow("com.example.app.MainActivity", none))
    }

    @Test
    fun `采样只取跳过类id与短文本且有界`() {
        val long = buildString { repeat(60) { append("字") } }
        val tree = n(children = listOf(
            n(viewId = "com.app:id/skip_view"),
            n(viewId = "com.app:id/content_body"),
            n(text = "跳过 3"),
            n(text = long),
            n(viewId = "com.app:id/close_icon"),
            n(text = "广告"),
            n(text = "立即跳转")
        ))
        val samples = AdWindowHeuristics.collectSamples(tree)
        assertTrue(samples.contains("#skip_view"))
        assertTrue(samples.contains("#close_icon"))
        assertTrue(samples.contains("跳过 3"))
        assertFalse("超长文本不入样本", samples.any { it.length > 48 })
        assertTrue("样本有界", samples.size <= 5)
    }

    @Test
    fun `敏感窗口不采样由调用方保证_启发式自身只判文本`() {
        // 启发式不做敏感判定（SafetyExclusions 已在引擎主链路拦截），此处仅回归文本判定
        val tree = n(text = "请输入密码")
        assertFalse(AdWindowHeuristics.isSuspectAdWindow("com.example.MainActivity", tree))
    }
}
