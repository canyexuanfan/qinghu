package top.hnwen17.guard.platform.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查更新弹窗：Release 正文 Markdown 清理为纯文本。 */
class AppUpdateCheckerTest {

    @Test
    fun `清理后不含 Markdown 符号且保留可读信息`() {
        val md = listOf(
            "## 轻护 v0.4.9 · 更新",
            "",
            "**重点**：修复体验",
            "",
            "| 文件 | 说明 |",
            "|---|---|",
            "| qinghu-standard.apk | 正式版 |",
            "",
            "- 项目一",
            "- [链接](https://example.com)",
            "",
            "使用 `代码` 标记"
        ).joinToString("\n")
        val out = AppUpdateChecker.plainText(md)
        assertFalse(out.contains("##"))
        assertFalse(out.contains("**"))
        assertFalse(out.contains("|---"))
        assertFalse(out.contains("`"))
        assertTrue(out.contains("轻护 v0.4.9 · 更新"))
        assertTrue(out.contains("重点：修复体验"))
        assertTrue(out.contains("文件 · 说明"))
        assertTrue(out.contains("qinghu-standard.apk · 正式版"))
        assertTrue(out.contains("· 项目一"))
        assertTrue(out.contains("链接（https://example.com）"))
        assertTrue(out.contains("使用 代码 标记"))
    }

    @Test
    fun `连续空行压缩且按行去首尾空白`() {
        val out = AppUpdateChecker.plainText("a\n\n\n\n b \n\n")
        assertFalse(out.contains("\n\n\n"))
        assertEquals("a\n\nb", out)
    }

    /** 更新源数据格式契约：update-check.json 字段缺失/带 v 前缀时的容错。 */
    @Test
    fun `版本比较语义化且容错`() {
        assertTrue(AppUpdateChecker.isNewer("0.5.3", "0.5.2"))
        assertTrue(AppUpdateChecker.isNewer("v0.6.0", "0.5.9"))
        assertTrue(AppUpdateChecker.isNewer("0.5.10", "0.5.9"))
        assertFalse(AppUpdateChecker.isNewer("0.5.2", "0.5.2"))
        assertFalse(AppUpdateChecker.isNewer("0.5.2", "0.5.3"))
        assertFalse(AppUpdateChecker.isNewer("0.5", "0.5.1"))
    }
}
