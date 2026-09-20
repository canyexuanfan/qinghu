package top.hnwen17.guard.core.rules

import top.hnwen17.guard.core.session.MonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun packJson(vararg rules: String, schema: Int = 1, id: String = "fixture.pack") = """
{"schemaVersion": $schema, "id": "$id", "version": 1,
 "provenance": {"author": "Qinghu", "license": "PROJECT-OWNED", "source": "self-authored-test-fixture"},
 "rules": [${rules.joinToString(",")}]}
""".trimIndent()

private fun ruleJson(
    id: String = "fixture.splash.close",
    pkg: String = "top.hnwen17.guard.probe.a",
    minV: Long = 1, maxV: Long = 1,
    viewId: String = "skip_button",
    parent: String? = "ad_container",
    textEquals: String? = null,
    actionType: String = "CLICK_VERIFIED_NODE",
    extra: String = ""
) = """
{"id": "$id", "version": 1,
 "provenance": {"author": "Qinghu", "license": "PROJECT-OWNED", "source": "self-authored-test-fixture"},
 "target": {"package": "$pkg", "minVersionCode": $minV, "maxVersionCode": $maxV},
 "match": {"viewId": "$viewId", "clickable": true${parent?.let { ", \"parentViewId\": \"$it\"" } ?: ""}${textEquals?.let { ", \"textEquals\": \"$it\"" } ?: ""}},
 "action": {"type": "$actionType", "maxAttempts": 2, "cooldownMs": 1500${postExtra(extra)}},
 "postcondition": {"absentViewId": "ad_container", "timeoutMs": 1000}}
""".trimIndent()

private fun postExtra(extra: String) = if (extra.isEmpty()) "" else ", $extra"

/** QH-P07-01/02：schema 与严格解析。 */
class RuleParserTest {

    private fun parse(json: String): RuleParser.RuleParseResult = RuleParser.parse(json.toByteArray(Charsets.UTF_8))

    @Test
    fun `合法包解析成功`() {
        val result = parse(packJson(ruleJson()))
        val ok = result as RuleParser.RuleParseResult.Ok
        assertEquals("fixture.pack", ok.pack.id)
        assertEquals(1, ok.pack.rules.size)
        assertEquals(RuleErrorCode.OK, RuleErrorCode.OK)
    }

    @Test
    fun `重复键被拒绝`() {
        val json = """{"schemaVersion": 1, "schemaVersion": 1, "id": "x", "version": 1}"""
        val result = parse(json)
        assertTrue(result is RuleParser.RuleParseResult.Error)
        assertEquals(RuleErrorCode.DUPLICATE_KEY, (result as RuleParser.RuleParseResult.Error).code)
    }

    @Test
    fun `未知schemaVersion被拒绝`() {
        val result = parse(packJson(ruleJson(), schema = 99))
        assertEquals(RuleErrorCode.UNKNOWN_SCHEMA_VERSION, (result as RuleParser.RuleParseResult.Error).code)
    }

    @Test
    fun `重复规则ID被拒绝`() {
        val result = parse(packJson(ruleJson(id = "r1"), ruleJson(id = "r1")))
        assertEquals(RuleErrorCode.DUPLICATE_RULE_ID, (result as RuleParser.RuleParseResult.Error).code)
    }

    @Test
    fun `未知动作类型被拒绝`() {
        val result = parse(packJson(ruleJson(actionType = "RUN_SHELL")))
        assertEquals(RuleErrorCode.UNKNOWN_ACTION_TYPE, (result as RuleParser.RuleParseResult.Error).code)
    }

    @Test
    fun `版本区间倒置被拒绝`() {
        val result = parse(packJson(ruleJson(minV = 5, maxV = 2)))
        assertEquals(RuleErrorCode.INVALID_VERSION_RANGE, (result as RuleParser.RuleParseResult.Error).code)
    }

    @Test
    fun `超长字符串被拒绝`() {
        val longId = "a".repeat(600)
        val result = parse(packJson(ruleJson(viewId = longId)))
        assertEquals(RuleErrorCode.STRING_TOO_LONG, (result as RuleParser.RuleParseResult.Error).code)
    }

    @Test
    fun `未知字段被拒绝`() {
        val json = packJson(ruleJson()) .replace("\"rules\":", "\"evil\": [], \"rules\":")
        val result = parse(json)
        assertTrue(result is RuleParser.RuleParseResult.Error, "未知字段必须拒绝")
    }

    @Test
    fun `空规则集被拒绝`() {
        val result = parse(packJson())
        assertEquals(RuleErrorCode.EMPTY_RULE_SET, (result as RuleParser.RuleParseResult.Error).code)
    }

    @Test
    fun `嵌套超深被拒绝`() {
        val depth = RuleLimits.MAX_NESTING_DEPTH + 5
        val sb = StringBuilder()
        repeat(depth) { sb.append("[") }
        sb.append("1")
        repeat(depth) { sb.append("]") }
        val result = parse(sb.toString())
        assertEquals(RuleErrorCode.NESTING_TOO_DEEP, (result as RuleParser.RuleParseResult.Error).code)
    }

    @Test
    fun `错误不抛异常穿透`() {
        // 各类畸形输入：只返回 Error，不抛
        for (bad in listOf("", "{", "[1,", "{\"a\":}", "null", "1.5", "1e9")) {
            val r = RuleParser.parse(bad.toByteArray())
            assertTrue(r is RuleParser.RuleParseResult.Error, "input '$bad' should be rejected")
        }
    }
}

/** QH-P07-04/05/07：索引与匹配。 */
class RuleIndexMatcherTest {

    private fun load(vararg rules: String): List<UiRule> {
        val ok = RuleParser.parse(packJson(*rules).toByteArray()) as RuleParser.RuleParseResult.Ok
        return ok.pack.rules
    }

    private fun node(viewId: String?, text: String? = null, clickable: Boolean = true, children: List<SnapshotNode> = emptyList()) =
        SnapshotNode(viewId, "android.widget.Button", text, clickable, children)

    @Test
    fun `索引按包过滤，无关包不遍历`() {
        val rules = load(ruleJson(), ruleJson(pkg = "com.other", id = "r2"))
        val index = RuleIndex.build(listOf(RulePack(1, "p", 1, RulePack.Provenance("a", "PROJECT-OWNED", "s"), rules)))
        assertEquals(1, index.candidatesFor("top.hnwen17.guard.probe.a", 1).size)
        assertEquals(0, index.candidatesFor("com.unknown", 1).size)
        assertTrue(index.hasPackage("com.other"))
        assertFalse(index.hasPackage("com.nope"))
    }

    @Test
    fun `版本区间过滤候选`() {
        val rules = load(ruleJson(minV = 1, maxV = 1), ruleJson(id = "r2", minV = 2, maxV = 9))
        val index = RuleIndex.build(listOf(RulePack(1, "p", 1, RulePack.Provenance("a", "PROJECT-OWNED", "s"), rules)))
        assertEquals(1, index.candidatesFor("top.hnwen17.guard.probe.a", 1).size)
        assertEquals(1, index.candidatesFor("top.hnwen17.guard.probe.a", 5).size)
    }

    @Test
    fun `viewId 不匹配短路失败（同文案负例）`() {
        val rules = load(ruleJson(textEquals = "跳过"))
        val snapshot = WindowSnapshot("top.hnwen17.guard.probe.a", 1, node("other_button", text = "跳过广告"))
        val matches = RuleMatcher.findMatches(rules.map { RuleIndex.IndexedRule(it, "p") }, snapshot)
        assertTrue(matches.isEmpty(), "viewId 不同则不因文案相同误命中")
    }

    @Test
    fun `命中正例：viewId+clickable+父约束`() {
        val rules = load(ruleJson())
        val ad = node("ad_container", clickable = false, children = listOf(node("skip_button")))
        val snapshot = WindowSnapshot("top.hnwen17.guard.probe.a", 1, node(null, clickable = false, children = listOf(ad)))
        val matches = RuleMatcher.findMatches(rules.map { RuleIndex.IndexedRule(it, "p") }, snapshot)
        assertEquals(1, matches.size)
        assertEquals("skip_button", matches[0].node.viewId)
    }

    @Test
    fun `页面约束：required 缺失或 forbidden 存在都拒绝`() {
        val constraint = UiRule.PageConstraint(listOf("ad_container"), listOf("sensitive_payment"))
        val ad = node("ad_container", clickable = false)
        val bad = WindowSnapshot("pkg", 1, node(null, clickable = false, children = listOf(ad, node("sensitive_payment", clickable = false))))
        val good = WindowSnapshot("pkg", 1, node(null, clickable = false, children = listOf(ad)))
        assertFalse(RuleMatcher.pageAllows(constraint, bad))
        assertTrue(RuleMatcher.pageAllows(constraint, good))
    }

    @Test
    fun `classNameSuffix 命中各 SDK 的跳过控件（通用层）`() {
        val rule = """
            {"id": "sdk.skip", "version": 1,
             "provenance": {"author": "Qinghu", "license": "PROJECT-OWNED", "source": "self-authored-test-fixture"},
             "target": {"package": "*", "minVersionCode": 0, "maxVersionCode": 999999},
             "match": {"classNameSuffix": "SkipView"},
             "action": {"type": "CLICK_VERIFIED_NODE", "maxAttempts": 1, "cooldownMs": 2000}}
        """.trimIndent()
        val rules = load(rule)
        val index = RuleIndex.build(listOf(RulePack(1, "p", 1, RulePack.Provenance("a", "PROJECT-OWNED", "s"), rules)))
        val csj = SnapshotNode(null, "com.bytedance.sdk.openadsdk.core.widget.SplashSkipView", "跳过", false, emptyList())
        val plain = SnapshotNode(null, "android.widget.TextView", "跳过", false, emptyList())
        val snapshot = WindowSnapshot("com.any.app", 1, SnapshotNode(null, null, null, false, listOf(csj, plain)))
        val matches = RuleMatcher.findMatches(index.candidatesFor("com.any.app", 1), snapshot)
        assertEquals(1, matches.size, "仅尾缀命中的 SDK 控件，普通文本节点不命中")
        assertEquals("com.bytedance.sdk.openadsdk.core.widget.SplashSkipView", matches[0].node.className)
    }

    @Test
    fun `深层跳过控件可达（QH-P18 实测：广告SDK嵌套超8层）`() {
        val rule = """
            {"id": "splash.broad", "version": 5,
             "provenance": {"author": "Qinghu", "license": "PROJECT-OWNED", "source": "self-authored-test-fixture"},
             "target": {"package": "*", "minVersionCode": 0, "maxVersionCode": 999999},
             "match": {"textContains": "跳过"},
             "action": {"type": "CLICK_VERIFIED_NODE", "maxAttempts": 1, "cooldownMs": 2000}}
        """.trimIndent()
        val rules = load(rule)
        val index = RuleIndex.build(listOf(RulePack(1, "p", 1, RulePack.Provenance("a", "PROJECT-OWNED", "s"), rules)))
        // 12 层深的「跳过 5」：复现趣盟开屏（样本可见、旧默认深度8不可达 → miss）
        var deep: SnapshotNode = SnapshotNode(null, "android.widget.TextView", "跳过 5", false, emptyList())
        repeat(11) { deep = SnapshotNode(null, "android.widget.FrameLayout", null, false, listOf(deep)) }
        val snapshot = WindowSnapshot("com.kmxs.reader", 1, deep)
        val matches = RuleMatcher.findMatches(index.candidatesFor("com.kmxs.reader", 1), snapshot)
        assertEquals(1, matches.size, "深度12的跳过文本必须可达")
        assertEquals("跳过 5", matches[0].node.text)
    }

    @Test
    fun `结构类规则_尺寸上限+纯图标+窗口广告文案闸门（GKD卡片关闭等价）`() {
        val rule = """
            {"id": "community.card", "version": 5,
             "provenance": {"author": "Qinghu", "license": "PROJECT-OWNED", "source": "self-authored-test-fixture"},
             "target": {"package": "*", "minVersionCode": 0, "maxVersionCode": 999999},
             "page": {"mustNotHave": ["sensitive_payment"]},
             "match": {"classNameSuffix": "ImageView", "textEmpty": true, "maxWidth": 90, "maxHeight": 90,
                        "windowTextContainsAny": ["立即了解", "广告"]},
             "action": {"type": "CLICK_VERIFIED_NODE", "maxAttempts": 1, "cooldownMs": 3000}}
        """.trimIndent()
        val rules = load(rule)
        val index = RuleIndex.build(listOf(RulePack(1, "p", 1, RulePack.Provenance("a", "PROJECT-OWNED", "s"), rules)))
        fun img(w: Int, h: Int) = SnapshotNode(null, "android.widget.ImageView", null, false, emptyList(),
            boundsInScreen = SnapshotNode.Bounds(0, 0, w, h))
        // 命中：小图标 + 窗口含广告文案
        val adCtx = WindowSnapshot("app", 1, SnapshotNode(null, null, null, false, listOf(
            img(60, 60), SnapshotNode(null, "android.widget.TextView", "广告", false, emptyList())
        )))
        assertEquals(1, RuleMatcher.findMatches(index.candidatesFor("app", 1), adCtx).size, "小图标+广告上下文应命中")
        // 拒绝：尺寸超限
        val bigCtx = WindowSnapshot("app", 1, SnapshotNode(null, null, null, false, listOf(
            img(500, 400), SnapshotNode(null, "android.widget.TextView", "广告", false, emptyList())
        )))
        assertEquals(0, RuleMatcher.findMatches(index.candidatesFor("app", 1), bigCtx).size, "大图不命中")
        // 拒绝：无广告文案上下文（防无差别点击图标）
        val plainCtx = WindowSnapshot("app", 1, SnapshotNode(null, null, null, false, listOf(
            img(60, 60), SnapshotNode(null, "android.widget.TextView", "普通内容", false, emptyList())
        )))
        assertEquals(0, RuleMatcher.findMatches(index.candidatesFor("app", 1), plainCtx).size, "无广告上下文不命中")
        // 拒绝：有文本的 ImageView（非纯图标）
        val withText = WindowSnapshot("app", 1, SnapshotNode(null, null, null, false, listOf(
            SnapshotNode(null, "android.widget.ImageView", "点我", false, emptyList(),
                boundsInScreen = SnapshotNode.Bounds(0, 0, 60, 60)),
            SnapshotNode(null, "android.widget.TextView", "广告", false, emptyList())
        )))
        assertEquals(0, RuleMatcher.findMatches(index.candidatesFor("app", 1), withText).size, "非纯图标不命中")
    }

    @Test
    fun `兄弟轴与子节点数匹配（GKD互动开屏等价）`() {
        val rule = """
            {"id": "community.interactive", "version": 6,
             "provenance": {"author": "Qinghu", "license": "PROJECT-OWNED", "source": "self-authored-test-fixture"},
             "target": {"package": "*", "minVersionCode": 0, "maxVersionCode": 999999},
             "match": {"clickable": true, "childCountMax": 1, "maxWidth": 300, "maxHeight": 200,
                        "siblingTextContainsAny": ["互动广告"]},
             "action": {"type": "CLICK_VERIFIED_NODE", "maxAttempts": 1, "cooldownMs": 3000}}
        """.trimIndent()
        val rules = load(rule)
        val index = RuleIndex.build(listOf(RulePack(1, "p", 1, RulePack.Provenance("a", "PROJECT-OWNED", "s"), rules)))
        val closeBtn = SnapshotNode(null, "android.view.View", null, true, emptyList(),
            boundsInScreen = SnapshotNode.Bounds(0, 0, 120, 80))
        val adTag = SnapshotNode(null, "android.widget.TextView", "互动广告", false, emptyList())
        val good = WindowSnapshot("app", 1, SnapshotNode(null, null, null, false, listOf(closeBtn, adTag)))
        assertEquals(1, RuleMatcher.findMatches(index.candidatesFor("app", 1), good).size, "兄弟含互动广告文本应命中")
        val noTag = WindowSnapshot("app", 1, SnapshotNode(null, null, null, false, listOf(closeBtn,
            SnapshotNode(null, "android.widget.TextView", "正常内容", false, emptyList()))))
        assertEquals(0, RuleMatcher.findMatches(index.candidatesFor("app", 1), noTag).size, "兄弟无互动广告不命中")
    }

    @Test
    fun `activityIds 白名单精确匹配且 fail-closed（阶段1）`() {
        val rule = """
            {"id": "act.white", "version": 1,
             "provenance": {"author": "Qinghu", "license": "PROJECT-OWNED", "source": "self-authored-test-fixture"},
             "target": {"package": "*", "minVersionCode": 0, "maxVersionCode": 999999},
             "match": {"activityIds": ["com.x.SplashActivity"], "textEquals": "跳过"},
             "action": {"type": "CLICK_VERIFIED_NODE", "maxAttempts": 1, "cooldownMs": 2000}}
        """.trimIndent()
        val rules = load(rule)
        val index = RuleIndex.build(listOf(RulePack(1, "p", 1, RulePack.Provenance("a", "PROJECT-OWNED", "s"), rules)))
        val node = SnapshotNode(null, "android.widget.TextView", "跳过", false, emptyList())
        // 命中：已确认 + Activity 全名一致
        val hit = WindowSnapshot("app", 1, node, activityId = "com.x.SplashActivity", activityConfirmed = true)
        assertEquals(1, RuleMatcher.findMatches(index.candidatesFor("app", 1, "com.x.SplashActivity", true), hit).size)
        // 已确认但 Activity 不同 → 不命中
        val other = WindowSnapshot("app", 1, node, activityId = "com.x.MainActivity", activityConfirmed = true)
        assertEquals(0, RuleMatcher.findMatches(index.candidatesFor("app", 1, "com.x.MainActivity", true), other).size)
        // 无法确认（fail-closed）：候选裁剪 + 匹配双重不命中
        val unconfirmedCands = index.candidatesFor("app", 1, null, false)
        assertEquals(0, unconfirmedCands.size, "无法确认 Activity 时规则被裁出候选集")
        val unconfirmedSnap = WindowSnapshot("app", 1, node, activityId = "com.x.SplashActivity", activityConfirmed = false)
        assertEquals(0, RuleMatcher.findMatches(index.candidatesFor("app", 1, null, false), unconfirmedSnap).size)
    }

    @Test
    fun `excludeActivityIds 命中黑名单则不匹配且 fail-closed（阶段1）`() {
        val rule = """
            {"id": "act.exclude", "version": 1,
             "provenance": {"author": "Qinghu", "license": "PROJECT-OWNED", "source": "self-authored-test-fixture"},
             "target": {"package": "*", "minVersionCode": 0, "maxVersionCode": 999999},
             "match": {"excludeActivityIds": ["com.x.VideoPlayerActivity"], "textEquals": "跳过"},
             "action": {"type": "CLICK_VERIFIED_NODE", "maxAttempts": 1, "cooldownMs": 2000}}
        """.trimIndent()
        val rules = load(rule)
        val index = RuleIndex.build(listOf(RulePack(1, "p", 1, RulePack.Provenance("a", "PROJECT-OWNED", "s"), rules)))
        val node = SnapshotNode(null, "android.widget.TextView", "跳过", false, emptyList())
        val safe = WindowSnapshot("app", 1, node, activityId = "com.x.MainActivity", activityConfirmed = true)
        assertEquals(1, RuleMatcher.findMatches(index.candidatesFor("app", 1, "com.x.MainActivity", true), safe).size)
        val excluded = WindowSnapshot("app", 1, node, activityId = "com.x.VideoPlayerActivity", activityConfirmed = true)
        assertEquals(0, RuleMatcher.findMatches(index.candidatesFor("app", 1, "com.x.VideoPlayerActivity", true), excluded).size)
        // fail-closed：无法确认 Activity → 不匹配
        val unknown = WindowSnapshot("app", 1, node, activityId = null, activityConfirmed = false)
        assertEquals(0, RuleMatcher.findMatches(index.candidatesFor("app", 1, null, false), unknown).size)
    }

    @Test
    fun `activity 字段畸形输入被解析层拒绝（阶段1）`() {
        for (bad in listOf(
            """{"id":"a","match":{"activityIds":[]},"action":{"type":"RECORD_ONLY"}}""",
            """{"id":"a","match":{"activityIds":["ok","has space"]},"action":{"type":"RECORD_ONLY"}}""",
            """{"id":"a","match":{"activityIds":["ab"]},"action":{"type":"RECORD_ONLY"}}"""
        )) {
            val r = RuleParser.parse(packJson(
                bad
            ).toByteArray())
            assertTrue(r is RuleParser.RuleParseResult.Error, "畸形 activity 列表应被拒绝: $bad")
        }
    }

    @Test
    fun `规则中文值UTF-8往返（词法器Latin-1逐字节曾是漏配根因）`() {
        val parsed = RuleParser.parse(packJson(ruleJson(textEquals = "跳过")).toByteArray()) as RuleParser.RuleParseResult.Ok
        assertEquals("跳过", parsed.pack.rules[0].match.textEquals, "中文规则值必须原样解码，乱码即永远无法命中")
    }

    @Test
    fun `viewIdContains与descContains命中（GKD社区通用规则等价落地）`() {
        val rule = """
            {"id": "community.skip", "version": 4,
             "provenance": {"author": "Qinghu", "license": "PROJECT-OWNED", "source": "self-authored-test-fixture"},
             "target": {"package": "*", "minVersionCode": 0, "maxVersionCode": 999999},
             "match": {"viewIdContains": "skip"},
             "action": {"type": "CLICK_VERIFIED_NODE", "maxAttempts": 1, "cooldownMs": 2000}}
        """.trimIndent()
        val rules = load(rule)
        val index = RuleIndex.build(listOf(RulePack(1, "p", 1, RulePack.Provenance("a", "PROJECT-OWNED", "s"), rules)))
        val byId = SnapshotNode("com.app:id/SkipView", "android.widget.View", null, false, emptyList())
        val noHit = SnapshotNode("com.app:id/title", "android.widget.TextView", null, false, emptyList())
        val matches = RuleMatcher.findMatches(
            index.candidatesFor("com.any.app", 1),
            WindowSnapshot("com.any.app", 1, SnapshotNode(null, null, null, false, listOf(byId, noHit)))
        )
        assertEquals(1, matches.size, "仅 skip 模糊 id 命中")
        // desc 通道
        val descRule = rule.replace("\"viewIdContains\": \"skip\"", "\"descContains\": \"跳过\"")
        val rules2 = load(descRule)
        val index2 = RuleIndex.build(listOf(RulePack(1, "p", 1, RulePack.Provenance("a", "PROJECT-OWNED", "s"), rules2)))
        val byDesc = SnapshotNode(null, "android.widget.ImageView", null, false, emptyList()).copy(desc = "跳过广告")
        val matches2 = RuleMatcher.findMatches(
            index2.candidatesFor("com.any.app", 1),
            WindowSnapshot("com.any.app", 1, SnapshotNode(null, null, null, false, listOf(byDesc)))
        )
        assertEquals(1, matches2.size, "desc 含跳过也应命中")
    }

    @Test
    fun `快照预算校验`() {
        val big = SnapshotNode(null, null, null, false, List(300) { node("n$it", clickable = false) })
        assertFalse(SnapshotNode.isWithinBudget(big))
        assertTrue(SnapshotNode.isWithinBudget(node("a")))
    }
}

/** QH-P07-08/09：优先级与冷却。 */
class RulePrioritySessionTest {

    @Test
    fun `优先级排序确定且最小干预优先`() {
        val a = ruleJson(id = "z_rule")
        val b = ruleJson(id = "a_rule", textEquals = "跳过")
        val parsed = listOf(a, b).map { (RuleParser.parse(packJson(it).toByteArray()) as RuleParser.RuleParseResult.Ok).pack.rules[0] }
        val sorted = RulePriority.sortedForExecution(parsed)
        assertEquals("a_rule", sorted[0].id, "更具体（含textEquals）者优先；并列按id稳定")
        // 重放确定性
        assertEquals(sorted, RulePriority.sortedForExecution(parsed))
    }

    @Test
    fun `冷却与最大尝试阻止无限点击`() {
        var now = 0L
        val clock = MonotonicClock { now }
        val state = RuleSessionState(clock)
        val rule = (RuleParser.parse(packJson(ruleJson()).toByteArray()) as RuleParser.RuleParseResult.Ok).pack.rules[0]
        assertTrue(state.canAttempt(rule, 1500))
        state.recordAttempt(rule, 1500, 1000)
        assertFalse(state.canAttempt(rule, 1500), "冷却期内")
        now = 1100
        assertFalse(state.canAttempt(rule, 1500), "后验观察期内")
        now = 2600
        assertTrue(state.canAttempt(rule, 1500))
        state.recordAttempt(rule, 1500, 0)
        now = 10_000
        assertFalse(state.canAttempt(rule, 1500), "maxAttempts=2 用尽")
    }

    @Test
    fun `会话切换整体重置`() {
        var now = 0L
        val clock = MonotonicClock { now }
        val state = RuleSessionState(clock)
        val rule = (RuleParser.parse(packJson(ruleJson()).toByteArray()) as RuleParser.RuleParseResult.Ok).pack.rules[0]
        state.recordAttempt(rule, 1500, 0)
        state.markSatisfied(rule)
        assertFalse(state.canAttempt(rule, 1500))
        state.onSessionSwitch()
        assertTrue(state.canAttempt(rule, 1500), "新会话重新开始")
        assertEquals(0, state.activeLedgers())
    }

    @Test
    fun `过期规则不参与`() {
        val json = ruleJson().replace("\"postcondition\"", "\"expiresAtEpochMs\": 100, \"postcondition\"")
        val rule = (RuleParser.parse(packJson(json).toByteArray()) as RuleParser.RuleParseResult.Ok).pack.rules[0]
        assertTrue(RuleValidator.isExpired(rule, nowEpochMs = 200))
        assertFalse(RuleValidator.isExpired(rule, nowEpochMs = 50))
        val pack = (RuleParser.parse(packJson(json).toByteArray()) as RuleParser.RuleParseResult.Ok).pack
        assertTrue(RuleValidator.usableRules(pack, nowEpochMs = 200).isEmpty())
    }
}
