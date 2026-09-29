package top.hnwen17.guard.platform.update

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 应用自更新检查（用户主动点击才联网，QH-隐私定位：默认不联网）。
 *
 * 四路径轮询+短超时快速降级（真机反馈：api.github.com 共享代理出口 60/h 限速、
 * GitHub 域名在无代理网络直连不可达）：
 * 1. cdn.jsdelivr.net（国内可达 CDN）：仓库 update-check.json（发布流程同步维护）
 * 2. fastly.jsdelivr.net（CDN 备源）
 * 3. api.github.com/releases/latest（含更新说明 body，有 60/h 限速）
 * 4. github.com/releases/latest 302 Location 解析版本号（无 API 限速）
 * lastError 记录最后失败路径原因，供 UI 透出诊断。
 */
object AppUpdateChecker {

    const val AUTHOR_SITE = "https://www.hnwen17.top"
    private const val API_LATEST = "https://api.github.com/repos/canyexuanfan/qinghu/releases/latest"
    private const val PAGE_LATEST = "https://github.com/canyexuanfan/qinghu/releases/latest"
    private const val CDN_MAIN = "https://cdn.jsdelivr.net/gh/canyexuanfan/qinghu@main/update-check.json"
    private const val CDN_FASTLY = "https://fastly.jsdelivr.net/gh/canyexuanfan/qinghu@main/update-check.json"
    private const val GHPROXY_RAW = "https://ghproxy.net/https://raw.githubusercontent.com/canyexuanfan/qinghu/main/update-check.json"
    private const val GHPROXY = "https://ghproxy.net/"

    data class LatestRelease(val version: String, val apkUrl: String, val notes: String)

    /** 最近一次失败原因（UI 透出诊断用）。 */
    @Volatile var lastError: String = ""
        private set

    /**
     * 联网检查最新 Release：CDN×2 → ghproxy→raw → API → 重定向，共 5 路径。
     * CDN 可达但版本不新时不直接返回（jsdelivr @main 分支缓存最长 12h，连发日会滞后），
     * 先记为兜底、继续走 GitHub 路径；GitHub 全挂时用 CDN 兜底结果。
     * 失败返回 null（lastError 有原因）。
     */
    fun fetchLatest(currentVersion: String): LatestRelease? {
        var cdnFallback: LatestRelease? = null
        for (url in listOf(CDN_MAIN, CDN_FASTLY)) {
            val r = fetchViaCdn(url) ?: continue
            if (isNewer(r.version, currentVersion)) { lastError = ""; return r }
            if (cdnFallback == null) cdnFallback = r
        }
        fetchViaCdn(GHPROXY_RAW)?.let { r ->
            if (isNewer(r.version, currentVersion)) { lastError = ""; return r }
        }
        fetchViaApi()?.let { lastError = ""; return it }
        fetchViaRedirect()?.let { lastError = ""; return it }
        cdnFallback?.let { lastError = ""; return it }
        if (lastError.isEmpty()) lastError = "网络连接失败"
        return null
    }

    /** 主路径：jsdelivr CDN 读 update-check.json（国内可达，无墙无限速）。 */
    private fun fetchViaCdn(url: String): LatestRelease? {
        return try {
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 8_000
            val code = conn.responseCode
            if (code !in 200..299) { lastError = "CDN HTTP " + code; return null }
            val json = JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val ver = json.optString("latest_version").removePrefix("v").trim()
            val apk = json.optString("apk_url").trim()
            if (ver.isEmpty() || apk.isEmpty()) { lastError = "CDN 数据不完整"; return null }
            LatestRelease(ver, apk, json.optString("notes", ""))
        } catch (_: Exception) {
            lastError = "CDN 不可达"
            null
        }
    }

    /** 备路径：GitHub API（带更新说明，60/h 限速）。 */
    private fun fetchViaApi(): LatestRelease? {
        return try {
            val conn = URL(API_LATEST).openConnection() as HttpsURLConnection
            conn.connectTimeout = 6_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code !in 200..299) { lastError = "HTTP " + code; return null }
            val json = JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val tag = json.optString("tag_name").removePrefix("v").trim()
            val assets = json.optJSONArray("assets") ?: return null.also { lastError = "发布数据不完整" }
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk") && name.contains("standard")) { apkUrl = a.optString("browser_download_url"); break }
            }
            if (apkUrl.isEmpty()) { lastError = "发布缺少安装包"; return null }
            LatestRelease(tag, apkUrl, plainText(json.optString("body")).take(600))
        } catch (e: Exception) {
            lastError = "连接失败：" + (e.message?.take(60) ?: "网络不可达")
            null
        }
    }

    /** 末路径：releases/latest 页面 302 的 Location 解析 tag（避开 api.github.com 限速）。 */
    private fun fetchViaRedirect(): LatestRelease? {
        return try {
            val conn = URL(PAGE_LATEST).openConnection() as HttpsURLConnection
            conn.connectTimeout = 6_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = false
            val loc = conn.getHeaderField("Location")
            if (loc.isNullOrEmpty()) { lastError = "无法获取最新版本"; return null }
            val tag = loc.substringAfterLast("/tag/").removePrefix("v").trim()
            if (tag.isEmpty()) { lastError = "无法解析版本号"; return null }
            LatestRelease(tag, "https://github.com/canyexuanfan/qinghu/releases/download/v" + tag + "/qinghu-standard-v" + tag + ".apk", "")
        } catch (e: Exception) {
            lastError = "连接失败：" + (e.message?.take(60) ?: "网络不可达")
            null
        }
    }

    /**
     * GitHub Release 正文（Markdown）→ 弹窗纯文本：
     * 标题去 #、粗体/斜体/代码符号剥离、表格行转「a · b」（分隔行丢弃）、
     * 链接转「文字（网址）」、列表符 - 改 ·、压缩多余空行。
     * GitHub 网页端渲染 Markdown 不受影响；此清理仅用于应用内弹窗。
     */
    fun plainText(markdown: String): String {
        val lines = markdown.lines().mapNotNull { raw ->
            val t = raw.trim()
            when {
                t.isEmpty() -> ""
                t.startsWith("#") -> t.trimStart('#').trim()
                Regex("^\\|?[\\s:|-]+\\|?$").matches(t) -> null
                t.startsWith("|") -> t.trim('|').split('|')
                    .joinToString(" · ") { it.trim() }.removeSuffix(" ·")
                else -> t
            }
        }
        return lines.joinToString("\n")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*\\n]+)\\*"), "$1")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)"), "$1（$2）")
            .replace(Regex("^- ", RegexOption.MULTILINE), "· ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** 语义比较：latest 是否新于 current（按数字分段，缺失段补 0）。 */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").trim().split(".").map { it.filter { c -> c.isDigit() }.ifEmpty { "0" }.toInt() }
        val l = parts(latest); val c = parts(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }; val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 下载安装包到外部私有目录：直连 → ghproxy 镜像；onProgress 回调 0-100（调用方保证 IO 线程）。 */
    fun downloadApk(context: Context, url: String, onProgress: (Int) -> Unit): File? {
        return downloadOnce(context, url, onProgress) ?: downloadOnce(context, GHPROXY + url, onProgress)
    }

    private fun downloadOnce(context: Context, url: String, onProgress: (Int) -> Unit): File? {
        return try {
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) return null
            val total = conn.contentLengthLong
            val dir = context.getExternalFilesDir(null)?.resolve("update")?.apply { mkdirs() } ?: return null
            val out = File(dir, "update.apk")
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L; var n: Int; var lastPct = -1
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n); read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }
}
