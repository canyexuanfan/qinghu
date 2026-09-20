package top.hnwen17.guard.core.rules

/**
 * QH-P18 观察日志启发式（core 纯函数，可单测）。
 *
 * 目的：引擎"看到"疑似广告窗口但**没有规则命中**时，留下本地观察记录——
 * 用户诉求：出现了广告就必须有痕迹（成功/失败/待适配），不能一片空白。
 *
 * 隐私合同：只在「疑似广告窗口」采样；样本 ≤5 条、每条 ≤48 字符；
 * 只存本机（与防护记录同目录），不上传；敏感窗口（支付/密码）一律不采样。
 */
object AdWindowHeuristics {

    /** 疑似广告的窗口类名标记（小写包含匹配；与 SensorAdBackGuard 的全屏广告 Activity 判定同源思路）。 */
    val AD_ACTIVITY_TOKENS = listOf(
        "openadsdk", "splashad", "adactivity", "interstitialad", "applovin",
        "adinterstitial", " rewardedad", "gdtad", "ksad", "sigmob", "mbridge",
        "mobads", "anythink", "pangle", "qm.ad"
    )

    /** 跳过/关闭类控件 id 关键词（小写包含匹配，用于采样而非点击）。 */
    val SKIP_ID_TOKENS = listOf("skip", "close", "countdown")

    /** 窗口是否疑似广告：类名线索命中广告标记，或窗口内存在跳过/关闭/广告标识文本。 */
    fun isSuspectAdWindow(classNameHint: String?, root: SnapshotNode): Boolean {
        val cls = classNameHint?.lowercase().orEmpty()
        if (AD_ACTIVITY_TOKENS.any { cls.contains(it) }) return true
        return hasAdText(root)
    }

    private fun hasAdText(n: SnapshotNode): Boolean {
        for (text in listOfNotNull(n.text, n.desc)) {
            if ("跳过" in text || "关闭广告" in text || "Skip" in text || "广告" in text) return true
        }
        return n.children.any { hasAdText(it) }
    }

    /** 采样线索：跳过/关闭类控件短 id 与窗口短文本（去重、有界、不含长文本）。 */
    fun collectSamples(root: SnapshotNode, limit: Int = 5): List<String> {
        val out = LinkedHashSet<String>()
        walk(root, out, limit)
        return out.toList()
    }

    private fun walk(n: SnapshotNode, out: LinkedHashSet<String>, limit: Int) {
        if (out.size >= limit) return
        n.viewId?.let { raw ->
            val short = raw.substringAfterLast('/')
            if (SKIP_ID_TOKENS.any { short.lowercase().contains(it) } && short.length <= 48) out.add("#$short")
        }
        if (out.size >= limit) return
        n.text?.takeIf { it.isNotBlank() && it.length <= 48 }?.let { out.add(it) }
        if (out.size >= limit) return
        n.desc?.takeIf { it.isNotBlank() && it.length <= 48 }?.let { out.add("@$it") }
        for (c in n.children) {
            walk(c, out, limit)
            if (out.size >= limit) return
        }
    }
}
