package top.hnwen17.guard.data.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.hnwen17.guard.core.rules.RuleParser

/** 用户自建规则仓库：包生成、schema 解析、去重、启停过滤。 */
class UserRuleStoreTest {

    @Test
    fun `TEXT 证据生成 textEquals 规则并带包名限定与后验`() {
        val store = UserRuleStore()
        store.add("com.video.app", "跳过", "TEXT", "SplashActivity")
        val bytes = store.packBytes()
        assertNotNull(bytes)
        val parsed = RuleParser.parse(bytes!!)
        val ok = parsed as RuleParser.RuleParseResult.Ok
        assertEquals("qinghu.user.rules", ok.pack.id)
        assertEquals(1, ok.pack.rules.size)
        val rule = ok.pack.rules[0]
        assertTrue(rule.id.startsWith("user."))
        assertEquals("跳过", rule.match.textEquals)
        assertEquals("com.video.app", rule.target.packageName)
        assertEquals("com.video.app", rule.target.packageName)
        assertNotNull(rule.postcondition)
        assertEquals("跳过", rule.postcondition!!.absentTextEquals)
    }

    @Test
    fun `VIEW_ID 证据生成 viewId 加 clickable 规则`() {
        val store = UserRuleStore()
        store.add("*", "skip_btn", "VIEW_ID", "")
        val ok = RuleParser.parse(store.packBytes()!!) as RuleParser.RuleParseResult.Ok
        assertEquals("skip_btn", ok.pack.rules[0].match.viewId)
        assertEquals(true, ok.pack.rules[0].match.clickable)
        assertNull(ok.pack.rules[0].postcondition)
    }

    @Test
    fun `同目标同证据去重`() {
        val store = UserRuleStore()
        store.add("com.a", "跳过", "TEXT", "")
        store.add("com.a", "跳过", "TEXT", "")
        assertEquals(1, store.all.value.size)
    }

    @Test
    fun `停用规则不进包，全部停用则包为空`() {
        val store = UserRuleStore()
        val r = store.add("com.a", "跳过", "TEXT", "")
        store.setEnabled(r.id, false)
        assertNull(store.packBytes())
        assertFalse(store.all.value[0].enabled)
    }

    @Test
    fun `删除规则即时生效`() {
        val store = UserRuleStore()
        val r = store.add("com.a", "跳过", "TEXT", "")
        store.remove(r.id)
        assertEquals(0, store.all.value.size)
        assertNull(store.packBytes())
    }

    @Test
    fun `通用词风险提示`() {
        assertTrue(UserRuleStore.isRiskyCommonWord("确定"))
        assertFalse(UserRuleStore.isRiskyCommonWord("跳过"))
    }
    @Test
    fun `样本翻译：类名样式串不出现，跳过样本自动推荐`() {
        val opts = UserRuleStore.presentableSamples(listOf("跳过", "51", "android.widget.FrameLayout"))
        assertEquals("跳过", opts.first().value)
        assertTrue(opts.first().recommended)
        assertTrue(opts.none { it.value == "android.widget.FrameLayout" })
    }

    @Test
    fun `样本翻译：viewId 含 skip 判为推荐进阶项`() {
        val opts = UserRuleStore.presentableSamples(listOf("#splash_ad_txt_skip", "跳过广告"))
        assertEquals(2, opts.size)
        assertEquals("跳过广告", opts.first().value) // TEXT 推荐排在最前
        assertTrue(opts[1].recommended)
    }

    @Test
    fun `样本翻译：跳过片头不是跳过按钮（播放器功能）`() {
        val opts = UserRuleStore.presentableSamples(listOf("跳过片头"))
        assertFalse(opts[0].recommended)
    }

    @Test
    fun `样本翻译：无可读线索返回空（UI 落到手动输入）`() {
        assertTrue(UserRuleStore.presentableSamples(listOf("androidx.recyclerview.widget.RecyclerView")).isEmpty())
    }

    @Test
    fun `样本翻译：desc 跳过广告判为推荐`() {
        val opts = UserRuleStore.presentableSamples(listOf("@跳过广告"))
        assertEquals("DESC", opts[0].kind)
        assertTrue(opts[0].recommended)
    }

    @Test
    fun `倒计时变体：跳过 5 判为推荐`() {
        val opts = UserRuleStore.presentableSamples(listOf("跳过 5"))
        assertTrue(opts[0].recommended)
    }

    // ===== 持久化（v0.5.4 起：磁盘为内部全量格式；此前读写格式分裂致更新后规则消失）=====

    @Test
    fun `内部格式回环：停用规则与排除片头片尾与备注全保留`() {
        val store = UserRuleStore()
        val a = store.add("com.a", "跳过", "TEXT", "SplashActivity")
        val b = store.add("com.b", "关闭", "TEXT_CONTAINS", "AdActivity", excludePlayerSkips = false)
        store.setEnabled(a.id, false)
        val text = UserRuleStore.internalJson(store)

        val restored = UserRuleStore()
        assertTrue(UserRuleStore.hydrate(restored, text))
        assertEquals(2, restored.all.value.size)
        val ra = restored.all.value.first { it.id == a.id }
        val rb = restored.all.value.first { it.id == b.id }
        assertFalse(ra.enabled)
        assertEquals("SplashActivity", ra.note)
        assertEquals("跳过", ra.buttonText)
        assertFalse(rb.excludePlayerSkips) // 创建时显式传 false，回环后不得变回默认 true
        assertEquals("TEXT_CONTAINS", rb.matchKind)
    }

    @Test
    fun `旧版规则包格式文件能救回导入（迁移路径）`() {
        // 旧版 save 误写 packBytes() 输出（JSON 对象、仅启用规则），管理页按数组解析必失败
        val legacy = UserRuleStore()
        legacy.add("com.video.app", "跳过", "TEXT", "SplashActivity")
        legacy.add("com.game.app", "点击跳过", "TEXT_CONTAINS", "GameActivity", excludePlayerSkips = false)
        val legacyFileText = String(legacy.packBytes()!!, Charsets.UTF_8)

        val restored = UserRuleStore()
        assertTrue(UserRuleStore.hydrate(restored, legacyFileText))
        assertEquals(2, restored.all.value.size)
        val textRule = restored.all.value.first { it.packageName == "com.video.app" }
        assertEquals("跳过", textRule.buttonText)
        assertEquals("TEXT", textRule.matchKind)
        assertTrue(textRule.enabled)
        val containsRule = restored.all.value.first { it.packageName == "com.game.app" }
        assertEquals("TEXT_CONTAINS", containsRule.matchKind)
        assertFalse(containsRule.excludePlayerSkips) // 旧包无 textNotContains → 反推为不排除
    }

    @Test
    fun `救回导入后再次打包与停用语义一致`() {
        val legacy = UserRuleStore()
        legacy.add("com.a", "跳过", "TEXT", "")
        val restored = UserRuleStore()
        UserRuleStore.hydrate(restored, String(legacy.packBytes()!!, Charsets.UTF_8))
        // 救回的规则可正常生成规则包（运行时索引可用）
        val ok = RuleParser.parse(restored.packBytes()!!) as RuleParser.RuleParseResult.Ok
        assertEquals(1, ok.pack.rules.size)
        assertEquals("跳过", ok.pack.rules[0].match.textEquals)
        // 停用后包为空（内部格式保留停用状态，包过滤启用项）
        restored.setEnabled(restored.all.value[0].id, false)
        assertNull(restored.packBytes())
        assertEquals(1, restored.all.value.size)
    }

    @Test
    fun `乱码文件不崩溃且不误导入`() {
        val restored = UserRuleStore()
        assertFalse(UserRuleStore.hydrate(restored, "not json at all {"))
        assertFalse(UserRuleStore.hydrate(restored, "{}"))
        assertEquals(0, restored.all.value.size)
    }
}
