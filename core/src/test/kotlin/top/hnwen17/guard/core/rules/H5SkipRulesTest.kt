package top.hnwen17.guard.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v17 H5 互动开屏规则回归：真实用户样本（腾讯视频互动广告 / 百度网盘跳过 5）
 * 与教程负例（NEG-001 同类：祖先无 id 且窗口无强广告标记不得命中）。
 */
class H5SkipRulesTest {

    private fun load(vararg rules: String): List<UiRule> {
        val json = """
{"schemaVersion": 1, "id": "qinghu.builtin.generic.zh", "version": 17,
 "provenance": {"author": "Qinghu", "license": "PROJECT-OWNED", "source": "test"},
 "rules": [${rules.joinToString(",")}]}
""".trimIndent()
        val ok = RuleParser.parse(json.toByteArray()) as RuleParser.RuleParseResult.Ok
        return ok.pack.rules
    }

    private fun node(viewId: String?, text: String? = null, clickable: Boolean = false, children: List<SnapshotNode> = emptyList()) =
        SnapshotNode(viewId, "android.widget.FrameLayout", text, clickable, children)

    private val skipH5 = """
{"id": "generic.splash.skip_h5", "version": 1,
 "provenance": {"author": "Qinghu", "license": "PROJECT-OWNED", "source": "test"},
 "target": {"package": "*", "minVersionCode": 0, "maxVersionCode": 999999},
 "match": {"textEquals": "跳过", "windowTextContainsAny": ["互动广告", "已Wi-Fi预加载"]},
 "action": {"type": "CLICK_VERIFIED_NODE", "maxAttempts": 2, "cooldownMs": 2000}}
""".trimIndent()

    private val skipH5Countdown = """
{"id": "generic.splash.skip_h5_countdown", "version": 1,
 "provenance": {"author": "Qinghu", "license": "PROJECT-OWNED", "source": "test"},
 "target": {"package": "*", "minVersionCode": 0, "maxVersionCode": 999999},
 "match": {"textContains": "跳过", "windowTextContainsAny": ["点击跳转至网页", "第三方应用"]},
 "action": {"type": "CLICK_VERIFIED_NODE", "maxAttempts": 2, "cooldownMs": 2000}}
""".trimIndent()

    @Test
    fun `腾讯视频H5互动开屏：跳过+互动广告标记命中`() {
        val rules = load(skipH5, skipH5Countdown)
        // 真机样本结构：H5 渲染节点无 viewId，跳过与广告标识同窗
        val window = node(null, children = listOf(
            node(null, text = "互动广告 | 已Wi-Fi预加载"),
            node(null, text = "跳过", clickable = true),
            node(null, text = "仙逆H5"),
            node(null, text = "原始传奇")
        ))
        val matches = RuleMatcher.findMatches(rules.map { RuleIndex.IndexedRule(it, "p") }, WindowSnapshot("com.tencent.qqlive", 1, window))
        assertEquals(1, matches.size)
        assertEquals("generic.splash.skip_h5", matches[0].rule.id)
    }

    @Test
    fun `百度网盘开屏：跳过5+法定跳转提示命中countdown规则`() {
        val rules = load(skipH5, skipH5Countdown)
        val window = node(null, children = listOf(
            node(null, text = "跳过 5", clickable = true),
            node(null, text = "点击跳转至网页或第三方应用")
        ))
        val matches = RuleMatcher.findMatches(rules.map { RuleIndex.IndexedRule(it, "p") }, WindowSnapshot("com.baidu.netdisk", 1, window))
        assertEquals(1, matches.size)
        assertEquals("generic.splash.skip_h5_countdown", matches[0].rule.id)
    }

    private val skipAdLabel = """
{"id": "generic.splash.skip_ad_label", "version": 1,
 "provenance": {"author": "Qinghu", "license": "PROJECT-OWNED", "source": "test"},
 "target": {"package": "*", "minVersionCode": 0, "maxVersionCode": 999999},
 "match": {"textEquals": "跳过", "windowTextEqualsAny": ["广告"]},
 "action": {"type": "CLICK_VERIFIED_NODE", "maxAttempts": 2, "cooldownMs": 2000}}
""".trimIndent()

    @Test
    fun `独立广告标签变体：跳过+全文等于广告命中`() {
        val rules = load(skipH5, skipAdLabel)
        // 真机样本（17:24:02）：窗口文本节点为独立的「广告」（非「互动广告」长句）
        val window = node(null, children = listOf(
            node(null, text = "广告"),
            node(null, text = "跳过", clickable = true),
            node(null, text = "仙逆H5")
        ))
        val matches = RuleMatcher.findMatches(rules.map { RuleIndex.IndexedRule(it, "p") }, WindowSnapshot("com.tencent.qqlive", 1, window))
        assertEquals(1, matches.size)
        assertEquals("generic.splash.skip_ad_label", matches[0].rule.id)
    }

    @Test
    fun `教程负例：含假广告字样长句不得触发独立标签规则`() {
        val rules = load(skipH5, skipAdLabel)
        // 探针 NEG-001 同构：教程窗口含「假广告fixture」子串但无全文等于「广告」的节点
        val window = node(null, children = listOf(
            node(null, text = "探针A · 假广告fixture（正例）"),
            node(null, text = "广告 3 秒后可跳过"),
            node("tutorial_skip", text = "跳过", clickable = true)
        ))
        val matches = RuleMatcher.findMatches(rules.map { RuleIndex.IndexedRule(it, "p") }, WindowSnapshot("top.hnwen17.guard.probe.a", 1, window))
        assertTrue(matches.isEmpty(), "长句中的广告字样不构成独立广告标签")
    }

    @Test
    fun `教程负例：跳过但窗口无强广告标记不得命中`() {
        val rules = load(skipH5, skipH5Countdown)
        // 与探针 NEG-001 同构：教程页「跳过」，窗口无「互动广告/已Wi-Fi预加载/点击跳转至网页」等强标记
        val window = node(null, children = listOf(
            node(null, text = "新手教程第1页：欢迎。"),
            node("tutorial_skip", text = "跳过", clickable = true)
        ))
        val matches = RuleMatcher.findMatches(rules.map { RuleIndex.IndexedRule(it, "p") }, WindowSnapshot("top.hnwen17.guard.probe.a", 1, window))
        assertTrue(matches.isEmpty(), "无强广告标记的教程跳过不得被 H5 规则误触")
    }
}
