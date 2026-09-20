package top.hnwen17.guard.platform.sensor

import top.hnwen17.guard.core.session.MonotonicClock

/**
 * 摇一摇广告无障碍级防御（QH-P10 实用路径）。
 *
 * 原理：摇一摇广告触发后会打开**全屏广告 Activity**（穿山甲/GDT/快手等 SDK 的
 * Activity 类名有公开可识别的标记）。检测到该类窗口即 BACK 返回——
 * **不需要 Shizuku/系统级传感器抑制**，纯无障碍能力即可消除大部分摇一摇广告。
 *
 * 与 SensorGate 的关系：
 * - SensorGate（系统级抑制）仍需 Shizuku+真机验证，保持 NO_GO；
 * - 本类是无障碍能力的独立防线（ADR 记录两条防线并列）。
 *
 * 合同：仅按 Activity 类名的**功能标识**判定（公开 SDK 结构，非版权表达）；
 * 白名单保守，误 BACK 成本低（用户按返回即可回到目标页）但冷却限频。
 */
class SensorAdBackGuard(
    private val clock: MonotonicClock,
    private val cooldownMs: Long = 3000L
) {

    private var lastBackAtMs: Long = -cooldownMs

    /** 公开广告 SDK Activity 类名的功能标记（小写包含匹配）。 */
    val classNameTokens = listOf(
        "openadsdk",        // 穿山甲 Pangle
        "splashad",         // 各 SDK 开屏
        "adactivity",       // 通用广告 Activity
        "interstitialad",   // 插屏
        "applovin",         // AppLovin
        "adinterstitial",
        " rewardedad",      // 前置空格避免误匹配（带空格的完整标记）
        "gdtad",            // 广点通
        "ksad",             // 快手广告
        // QH-P18 通用性扩充：同类公开 SDK 结构标记（小写包含匹配，误 BACK 成本=一次返回）
        "sigmob",           // Sigmob
        "mbridge",          // Mintegral
        "mobads",           // 百度广告（com.baidu.mobads.*，不误伤普通百度包名）
        "anythink",         // TopOn/AnyThink
        "pangle",           // 穿山甲国际版
        "qm.ad"             // 趣盟（com.qm.ad:qumeng，用户真机日志确认的摇一摇开屏 SDK）
    )

    /**
     * @return true=应当 BACK（调用方执行 performGlobalAction(GLOBAL_ACTION_BACK)）
     */
    fun shouldBack(className: String?, nowMs: Long): Boolean {
        val cls = className?.lowercase() ?: return false
        if (nowMs - lastBackAtMs < cooldownMs) return false
        val hit = classNameTokens.any { cls.contains(it) }
        if (hit) lastBackAtMs = nowMs
        return hit
    }
}
