package top.hnwen17.guard.core.rules

/**
 * 规则 Schema（QH-P07-01，契约见 docs/TECHNICAL_DESIGN.md 第5章）。
 *
 * 首版只支持已知匹配器：包名、版本范围、viewId、类名、有限文本、clickable、
 * 父子/兄弟关系（限深）。**不接受**任意正则、XPath 函数、脚本、shell、外部 URL 动作。
 * 未知 schemaVersion / 未知动作 / 超限字段一律拒绝（错误码见 [RuleErrorCode]）。
 */
data class RulePack(
    val schemaVersion: Int,
    val id: String,
    val version: Int,
    val provenance: Provenance,
    val rules: List<UiRule>
) {
    data class Provenance(val author: String, val license: String, val source: String)
}

data class UiRule(
    val id: String,
    val version: Int,
    val provenance: RulePack.Provenance,
    val target: Target,
    val page: PageConstraint?,
    val match: MatchCondition,
    val action: RuleAction,
    val postcondition: Postcondition?,
    val expiresAtEpochMs: Long?
) {
    data class Target(val packageName: String, val minVersionCode: Long, val maxVersionCode: Long)

    data class PageConstraint(
        val requiredViewIds: List<String>,
        val mustNotHaveViewIds: List<String>
    )

    data class MatchCondition(
        val viewId: String?,
        /** QH-P18：viewId 短名包含匹配（GKD 社区通用规则 vid~=.*skip.* 的等价落地）。 */
        val viewIdContains: String?,
        val className: String?,
        /** QH-P18 通用性：SDK 类名尾缀匹配（如 SkipView 命中各家 SplashSkipView 类）。 */
        val classNameSuffix: String?,
        val textEquals: String?,
        val textContains: String?,
        /** QH-P18：contentDescription 包含匹配（部分 SDK 跳过控件只有 desc）。 */
        val descContains: String?,
        val clickable: Boolean?,
        val parentViewId: String?,
        /** QH-P18 结构类（GKD 卡片关闭规则等价子集）：控件屏幕宽度上限（px）。 */
        val maxWidth: Int? = null,
        /** 控件屏幕高度上限（px）。 */
        val maxHeight: Int? = null,
        /** 要求文本为空（GKD text=""：信息流关闭按钮为纯图标）。 */
        val textEmpty: Boolean? = null,
        /** 窗口内需存在任一广告文案（结构规则的上下文闸门，防无差别点击图标）。 */
        val windowTextContainsAny: List<String>? = null,
        /** 窗口内需存在全文（trim 后）等于任一值的节点（如规范要求的独立「广告」标签；
         *  与 windowTextContainsAny 的子串匹配不同，不受长句中偶现字样干扰）。 */
        val windowTextEqualsAny: List<String>? = null,
        /** 窗口内需存在 1-3 位纯数字独立文本节点（开屏倒计时证据，
         *  如小度开屏的「跳过」+「51」；正常功能页极少出现独立纯数字节点）。 */
        val windowHasNumericText: Boolean? = null,
        /** 窗口内需存在任一 viewId 关键词（李跳跳「广告容器出现才点关闭」语义）。 */
        val windowViewIdContainsAny: List<String>? = null,
        /** 祖先链上任一 viewId 短名需含任一标记（节点级广告容器证据）。
         *  窗口级文本证据（windowTextContainsAny）无法区分同窗口内的正/负例——
         *  教程「跳过」与广告「跳过」同窗时，只有「挂在广告容器下」才是可判别信号。 */
        val ancestorViewIdContainsAny: List<String>? = null,
        /** QH-P18 兄弟轴（GKD childCount 等价）：子节点数上限。 */
        val childCountMax: Int? = null,
        /** 子节点数精确匹配。 */
        val childCountEquals: Int? = null,
        /** 要求为父节点的最后一个子节点（GKD index=parent.childCount.minus(1)）。 */
        val lastChild: Boolean? = null,
        /** 兄弟子树中需存在任一文本（GKD -(1,2) [text=…] 兄弟轴的近似落地）。 */
        val siblingTextContainsAny: List<String>? = null,
        /** QH-阶段1：Activity 全类名白名单（精确匹配）。仅当采集端能确认事件类名
         *  确为 Activity（TYPE_WINDOW_STATE_CHANGED）时参与匹配；无法确认时按不匹配
         *  处理（fail-closed）。≤8 项、单项 ≤512B，解析层校验。 */
        val activityIds: List<String>? = null,
        /** Activity 全类名黑名单：确认的 Activity 命中其一则不匹配；无法确认时不匹配（fail-closed）。 */
        val excludeActivityIds: List<String>? = null,
        val maxDepth: Int = RuleLimits.DEFAULT_MAX_DEPTH
    )

    data class RuleAction(
        val type: ActionType,
        val maxAttempts: Int,
        val cooldownMs: Long
    ) {
        enum class ActionType {
            /** 对已验证节点执行一次点击（Cleaner 首版唯一主动动作）。 */
            CLICK_VERIFIED_NODE,

            /** 仅记录观察结果，不产生任何界面副作用。 */
            RECORD_ONLY
        }
    }

    data class Postcondition(
        val absentViewId: String,
        val timeoutMs: Long,
        /** H5 广告无 viewId：以独立文本（trim 全文等于）是否消失作为关闭依据，如「互动广告」。 */
        val absentTextEquals: String? = null
    )
}

/** 解析/校验错误码：稳定枚举，供上层映射为用户可读文案与诊断日志。 */
enum class RuleErrorCode {
    OK,
    MALFORMED_JSON,
    DUPLICATE_KEY,
    DUPLICATE_RULE_ID,
    UNKNOWN_SCHEMA_VERSION,
    MISSING_REQUIRED_FIELD,
    UNKNOWN_ACTION_TYPE,
    STRING_TOO_LONG,
    NESTING_TOO_DEEP,
    TOO_MANY_RULES,
    PACKAGE_TOO_LARGE,
    INVALID_VERSION_RANGE,
    MISSING_LICENSE_PROVENANCE,
    UNREVIEWED_PROVENANCE,
    EXPIRED_RULE,
    EMPTY_RULE_SET
}

/** 限额常量：初始预算（变动须 ADR + 实测，见技术合同）。 */
object RuleLimits {
    const val SCHEMA_VERSION = 1
    const val MAX_STRING_BYTES = 512
    const val MAX_NESTING_DEPTH = 32
    const val MAX_RULES = 10_000
    const val MAX_PACKAGE_BYTES = 8L * 1024 * 1024
    const val MAX_NODES_PER_WINDOW = 256
    const val MAX_CANDIDATES = 100
    /**
     * QH-P18 实测（用户观察日志）：广告 SDK 的跳过控件嵌套深度 >8 层，默认 8 会导致
     * 「样本里可见、匹配器不可达」的漏配。对齐快照预算上限（快照本身受 MAX_NESTING_DEPTH
     * 约束，匹配深度不再另行设卡；单条规则仍受 findNode 首个命中即返回的有界遍历保护）。
     * 注意：用 val 不用 const——const 会被跨文件内联，增量编译下旧值可能残留。
     */
    val DEFAULT_MAX_DEPTH = 32
    const val MAX_VIEW_IDS_PER_PAGE = 16
    const val MAX_ATTEMPTS = 2
    const val MAX_COOLDOWN_MS = 60_000L
}
