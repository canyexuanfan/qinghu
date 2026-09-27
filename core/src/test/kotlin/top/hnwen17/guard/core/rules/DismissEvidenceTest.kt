package top.hnwen17.guard.core.rules

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 退出前验证证据判定单测（v0.4.6 跳转拦截放行闸门）。 */
class DismissEvidenceTest {

    private fun node(text: String? = null, children: List<SnapshotNode> = emptyList()) =
        SnapshotNode(null, "android.widget.TextView", text, false, children)

    @Test
    fun `视频播放页：跳过片头按钮与时间戳不构成广告证据`() {
        val player = node("01:23:45", children = listOf(
            node("跳过片头"),
            node("全屏")
        ))
        assertFalse(AdWindowHeuristics.hasDismissEvidence(player, "com.video.app.PlayerActivity"))
    }

    @Test
    fun `开屏广告页：跳过按钮与倒计时构成证据`() {
        val splash = node(null, children = listOf(
            node("跳过"),
            node("51")
        ))
        assertTrue(AdWindowHeuristics.hasDismissEvidence(splash, "com.video.app.SplashActivity"))
    }

    @Test
    fun `倒计时变体：跳过 5 构成证据`() {
        val splash = node(null, children = listOf(node("跳过 5")))
        assertTrue(AdWindowHeuristics.hasDismissEvidence(splash))
    }

    @Test
    fun `强广告标识：互动广告与关闭广告构成证据`() {
        assertTrue(AdWindowHeuristics.hasDismissEvidence(node(null, children = listOf(node("互动广告 | 已Wi-Fi预加载")))))
        assertTrue(AdWindowHeuristics.hasDismissEvidence(node(null, children = listOf(node("关闭广告")))))
    }

    @Test
    fun `类名命中广告SDK特征即构成证据（含跳过片头的页面）`() {
        val player = node("跳过片头")
        assertTrue(AdWindowHeuristics.hasDismissEvidence(
            player, "com.anythink.core.basead.ui.web.WebLandPageActivity"))
    }

    @Test
    fun `普通页面：无任何广告证据不构成`() {
        val normal = node(null, children = listOf(
            node("首页"),
            node("消息"),
            node("我的")
        ))
        assertFalse(AdWindowHeuristics.hasDismissEvidence(normal, "com.app.MainActivity"))
    }
}