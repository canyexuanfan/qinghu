package top.hnwen17.guard.platform.jump

import top.hnwen17.guard.core.session.MonotonicClock

/**
 * 跳转拦截判定器（来源识别版，2026-09-22 按用户指导重构）。
 *
 * 核心原则：**所有需要禁掉的跳转都来自广告**——判定不猜测用户意图
 * （点击信号/驻留时长都只是间接证据，真机多轮反馈证明两者都会误拦漏拦），
 * 而是识别跳转来源是否为广告：
 *
 * 来源证据（任一命中即广告跳转，由服务层计算传入 [adOrigin]）：
 * - 引擎 3 秒内刚点击过广告控件（误点诱饵后的拉起也拦）；
 * - 源应用 5 秒内广告仍在场（FAILED/EXECUTED/观察记录=广告未成功关闭）；
 * - 目标 Activity 命中广告落地页特征（anythink WebLandPageActivity 等）。
 *
 * 非广告来源（用户分享文件、系统弹窗、正常跨包操作）→ 一律放行，
 * 通知栏/分享面板误拦问题从模型上消除。
 */
class JumpInterceptor(
    private val clock: MonotonicClock
) {

    data class Decision(val action: Action, val reason: String)

    enum class Action { OBSERVE, BLOCK_BACK, ALLOW_SENSITIVE, ALLOW_NORMAL }

    /**
     * 跳转候选判定。
     * @param adOrigin 来源广告证据（引擎近期点击 / 源应用广告在场 / 目标落地页特征）
     */
    fun decide(
        fromPackage: String,
        toPackage: String,
        jumpEnabled: Boolean,
        targetSensitive: Boolean,
        adOrigin: Boolean
    ): Decision {
        if (toPackage == fromPackage) return Decision(Action.OBSERVE, "same package")
        // 敏感目标一律放行（支付/登录优先于拦截）
        if (targetSensitive) return Decision(Action.ALLOW_SENSITIVE, "sensitive target")
        // 未显式开启：只观测
        if (!jumpEnabled) return Decision(Action.OBSERVE, "jump not enabled for " + fromPackage)
        // 来源识别：广告引起的跳转一律拦截（含误点诱饵后的拉起）
        if (adOrigin) return Decision(Action.BLOCK_BACK, "ad-originated jump " + fromPackage + " -> " + toPackage)
        // 无广告证据：用户正常跨包操作（分享/打开方式/系统弹窗），放行
        return Decision(Action.ALLOW_NORMAL, "no ad evidence for " + fromPackage + " -> " + toPackage)
    }
}
