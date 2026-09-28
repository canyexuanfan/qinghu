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
}
