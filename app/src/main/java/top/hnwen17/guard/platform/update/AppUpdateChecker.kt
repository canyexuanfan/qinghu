package top.hnwen17.guard.platform.update

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 应用自更新检查（用户主动点击才联网，QH-隐私定位：默认不联网）。
 *
 * 数据源：公开 GitHub Release（latest）。
 * - 版本比对：tag_name（vX.Y.Z）按数字分段与当前版本比较；
 * - 安装包：assets 中名称含 "standard" 且 .apk 结尾的正式包；
 * - 下载落点：外部私有目录 update/update.apk（FileProvider 授权安装器读取）。
 */
object AppUpdateChecker {

    const val AUTHOR_SITE = "https://www.hnwen17.top"
    private const val API_LATEST = "https://api.github.com/repos/canyexuanfan/qinghu/releases/latest"

    data class LatestRelease(val version: String, val apkUrl: String, val notes: String)

    data class ReleaseInfo(val version: String, val apkUrl: String, val notes: String)

    /** 联网检查最新 Release；失败返回 null（调用方提示检查网络）。 */
    fun fetchLatest(): LatestRelease? {
        return try {
        val conn = URL(API_LATEST).openConnection() as HttpsURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode !in 200..299) return null
        val json = JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        val tag = json.optString("tag_name").removePrefix("v").trim()
        if (tag.isEmpty()) return null
        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl = ""
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.endsWith(".apk") && name.contains("standard")) { apkUrl = url; break }
        }
        if (apkUrl.isEmpty()) return null
        LatestRelease(tag, apkUrl, json.optString("body").take(600))
        } catch (_: Exception) {
            null
        }
    }

    /** 语义比较：latest 是否新于 current（按数字分段，缺失段补 0）。 */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").trim().split('.').map { it.filter { c -> c.isDigit() }.ifEmpty { "0" }.toInt() }
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
