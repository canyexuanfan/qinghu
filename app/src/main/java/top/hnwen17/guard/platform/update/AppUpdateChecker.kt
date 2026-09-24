package top.hnwen17.guard.platform.update

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 应用自更新检查（用户主动点击才联网，QH-隐私定位：默认不联网）。
 *
 * 双路径（真机反馈 api.github.com 在共享代理出口易触发 60/h 限速）：
 * - 主路径：api.github.com/releases/latest（含更新说明 body）；
 * - 备路径：github.com/releases/latest 的 302 Location 解析版本号（无 API 限速），
 *   附件名按发布约定构造：qinghu-standard-vX.Y.Z.apk。
 * lastError 记录最近一次失败原因，供 UI 透出诊断。
 */
object AppUpdateChecker {

    const val AUTHOR_SITE = "https://www.hnwen17.top"
    private const val API_LATEST = "https://api.github.com/repos/canyexuanfan/qinghu/releases/latest"
    private const val PAGE_LATEST = "https://github.com/canyexuanfan/qinghu/releases/latest"

    data class LatestRelease(val version: String, val apkUrl: String, val notes: String)

    /** 最近一次失败原因（UI 透出诊断用）。 */
    @Volatile var lastError: String = ""
        private set

    /** 联网检查最新 Release：主路径 API → 备路径重定向。失败返回 null（lastError 有原因）。 */
    fun fetchLatest(): LatestRelease? {
        fetchViaApi()?.let { lastError = ""; return it }
        val viaRedirect = fetchViaRedirect()
        if (viaRedirect == null && lastError.isEmpty()) lastError = "网络连接失败"
        return viaRedirect
    }

    /** 主路径：GitHub API（带更新说明）。 */
    private fun fetchViaApi(): LatestRelease? {
        return try {
            val conn = URL(API_LATEST).openConnection() as HttpsURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
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
            LatestRelease(tag, apkUrl, json.optString("body").take(600))
        } catch (e: Exception) {
            lastError = "连接失败：" + (e.message?.take(60) ?: "网络不可达")
            null
        }
    }

    /** 备路径：releases/latest 页面 302 的 Location 解析 tag（避开 api.github.com 限速）。 */
    private fun fetchViaRedirect(): LatestRelease? {
        return try {
            val conn = URL(PAGE_LATEST).openConnection() as HttpsURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
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

    /** 下载安装包到外部私有目录；onProgress 回调 0-100（调用方保证 IO 线程）。 */
    fun downloadApk(context: Context, url: String, onProgress: (Int) -> Unit): File? {
        return try {
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.connectTimeout = 10_000
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