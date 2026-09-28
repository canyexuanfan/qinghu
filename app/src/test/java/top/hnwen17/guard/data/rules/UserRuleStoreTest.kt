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
}