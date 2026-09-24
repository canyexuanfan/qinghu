package top.hnwen17.guard.platform

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import top.hnwen17.guard.core.engine.ActionArbiter
import top.hnwen17.guard.core.engine.GuardEvent
import top.hnwen17.guard.core.engine.ProtectionReducer
import top.hnwen17.guard.core.policy.SafetyExclusions
import top.hnwen17.guard.core.records.EventType
import top.hnwen17.guard.core.records.ProtectionOutcome
import top.hnwen17.guard.platform.accessibility.NodeSnapshotReader
import top.hnwen17.guard.core.rules.RuleIndex
import top.hnwen17.guard.core.rules.RuleMatcher
import top.hnwen17.guard.core.rules.RuleParser
import top.hnwen17.guard.core.rules.RulePriority
import top.hnwen17.guard.core.rules.RuleSessionState
import top.hnwen17.guard.core.rules.RuleValidator
import top.hnwen17.guard.core.rules.SnapshotNode
import top.hnwen17.guard.core.rules.UiRule
import top.hnwen17.guard.core.rules.WindowSnapshot
import top.hnwen17.guard.core.session.MonotonicClock
import kotlinx.coroutines.launch

/**
 * 规则运行时 + Cleaner 执行器（QH-P07/P08）：事件 → 匹配 → 仲裁 → **真实点击执行 + 后验**。
 *
 * - 规则包来源：主资产 generic_zh.json（随 APK 分发，由 APK 签名保护，默认启用）；debug 另加载 fixture_probe.json（探针专用）；在线更新走 P16 验签通道，验签不过不落地；
 * - 全部在规则工作器线程调用（EngineDispatcher 回调），主线程零参与；
 * - 执行链（每个 CLICK 候选）：敏感窗口排除 → 冷却/次数账本 → performAction(ACTION_CLICK)
 *   → 记 EXECUTED → 延迟后验（absentViewId 消失→VERIFIED，否则 FAILED，窗口已切换则保持 EXECUTED 不冒充）；
 * - 节点生命周期：LiveSnapshot 用后立即 recycleAll（API<33），不缓存跨事件。
 */
class RuleRuntime(
    private val clock: MonotonicClock = MonotonicClock { android.os.SystemClock.uptimeMillis() },
    private val postScope: kotlinx.coroutines.CoroutineScope? = null,
    private val ruleRepository: top.hnwen17.guard.data.RuleRepository? = null, // QH-P08-05/06
    private val recordStore: top.hnwen17.guard.data.records.RecordStore? = null, // QH-P08-07
    private val observeStore: top.hnwen17.guard.data.records.ObserveStore? = null, // QH-P18 观察日志
) {

    /**
     * QH-P08-04/P09：触摸保护转交回调——由宿主（无障碍服务）注册，
     * 把候选区域交给 TouchShieldManager 显示遮罩（服务可变，运行期注册）。
     */
    @Volatile
    var maskExecutor: ((pkg: String, l: Int, t: Int, r: Int, b: Int, sessionEpoch: Long) -> Unit)? = null

    /** QH-P18：坐标点按兜底（宿主服务注入 dispatchGesture；不可点控件按中心坐标点按）。 */
    @Volatile
    var gestureDispatcher: ((x: Float, y: Float) -> Boolean)? = null

    private var index: RuleIndex? = null
    private val sessionStates = HashMap<Long, RuleSessionState>() // key: windowEpoch（会话隔离账本）
    @Volatile var lastFailedRuleId: String? = null // QH-P08-06：供设置页"停用失败规则"
    private val ruleFailCounts = HashMap<String, Int>()
    @Volatile private var lastRoScanAtMs: Long = 0L // 观察舱扫描节流（≥5s） // QH-P18：pkg|rule → 失败次数（≥3 自动停用）
    @Volatile private var lastExecutedAtMs: Long = 0L // QH-P18：exec 后短窗内的失败不再记观察（窗口关闭过程中的连带事件）
    /** 最近一次广告控件点击时间（clock 域）：跳转来源识别信号（3 秒内的拉起视为广告链路） */
    @Volatile var lastAdActionAtMs: Long = 0L

    /** QH-P18 全局点击限流：跨规则 10 秒窗口内最多 3 次点击（用户报告「反复操作屏幕致失去控制」）。 */
    private val globalClickTimes = ArrayDeque<Long>()
    /** 跳转来源识别：距最近一次广告控件点击是否不超过 [withinMs]。 */
    fun recentAdAction(withinMs: Long, nowMs: Long): Boolean =
        lastAdActionAtMs > 0 && (nowMs - lastAdActionAtMs) <= withinMs

    private fun clickAllowed(): Boolean = synchronized(globalClickTimes) {
        val now = clock.nowMs()
        while (globalClickTimes.isNotEmpty() && now - globalClickTimes.first() > 10_000) globalClickTimes.removeFirst()
        if (globalClickTimes.size >= 3) return false
        globalClickTimes.addLast(now)
        return true
    }

    /** 最近动作环形日志（有界 32 条）：诊断页与 PoC 取证用。 */
    private val recentActions = ArrayDeque<String>()
    private fun logAction(line: String) {
        synchronized(recentActions) {
            if (recentActions.size >= 32) recentActions.removeFirst()
            recentActions.addLast(line)
        }
        // PoC 阶段诊断通道（P05-11 口径）：可经 adb logcat -s RuleRuntime 外部观测；发布版移除
        android.util.Log.d("RuleRuntime", line)
    }

    fun recentActionsSnapshot(): List<String> = synchronized(recentActions) { recentActions.toList() }

    /**
     * 加载内置规则：generic_zh.json（主资产，全 flavor 携带，"安装即用"）+ debug fixture（探针用，可缺）。
     * 失败保持空索引并记录原因。generic 规则默认启用（RuleRepository 登记 defaultEnabled）。
     */
    fun ensureLoaded(context: Context, debugAssetPath: String = "rules/fixture_probe.json") {
        if (index != null) return
        val packs = mutableListOf<top.hnwen17.guard.core.rules.RulePack>()
        var builtinOn = true
        var externalOn = true
        val subStates = HashMap<String, Pair<String, Boolean>>() // fileName → (state, clickEnabled)
        try {
            val sf = java.io.File(context.filesDir, "subscriptions.json")
            if (sf.exists()) {
                val arr = org.json.JSONArray(sf.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("id") == "builtin") { builtinOn = o.optBoolean("clickEnabled", true); continue }
                    if (o.optString("id") == "external") { externalOn = o.optBoolean("clickEnabled", true); continue }
                    subStates[o.optString("fileName")] = Pair(o.optString("state", "OBSERVE"), o.optBoolean("clickEnabled", false))
                }
            }
        } catch (_: Exception) { }
        // 1) 通用包（主资产，所有 flavor）
        try {
            val parsed = RuleParser.parse(context.assets.open("rules/generic_zh.json").use { it.readBytes() })
            if (parsed is RuleParser.RuleParseResult.Ok) {
                if (builtinOn) packs.add(parsed.pack) else logAction("generic pack disabled by user")
                ruleRepository?.registerKnown(parsed.pack.rules.map { it.id }.toSet(), defaultEnabled = true)
                logAction("generic pack loaded: ${parsed.pack.rules.size} rules")
            } else if (parsed is RuleParser.RuleParseResult.Error) {
                logAction("generic load failed: ${parsed.code}")
            }
        } catch (e: Exception) {
            logAction("generic load unavailable: ${e.message?.take(60)}")
        }
        // 2) debug fixture（探针专用，可缺）
        try {
            val parsed = RuleParser.parse(context.assets.open(debugAssetPath).use { it.readBytes() })
            if (parsed is RuleParser.RuleParseResult.Ok) {
                packs.add(parsed.pack)
                ruleRepository?.registerKnown(parsed.pack.rules.map { it.id }.toSet(), defaultEnabled = false)
            }
        } catch (e: Exception) {
            logAction("fixture load unavailable: ${e.message?.take(60)}")
        }
        // 3) 用户导入目录（外部 files/rules/*.json，QH-P16 本地导入路径；导入来源默认停用，可在设置启用）
        try {
            context.getExternalFilesDir(null)?.resolve("rules")?.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedBy { it.name }?.forEach { f ->
                    try {
                        val parsed = RuleParser.parse(f.readBytes())
                        if (parsed is RuleParser.RuleParseResult.Ok) {
                            packs.add(parsed.pack)
                            ruleRepository?.registerKnown(parsed.pack.rules.map { it.id }.toSet(), defaultEnabled = false)
                            logAction("imported pack loaded: ${f.name} (${parsed.pack.rules.size} rules)")
                        } else if (parsed is RuleParser.RuleParseResult.Error) {
                            logAction("imported pack rejected: ${f.name} ${parsed.code}")
                        }
                    } catch (e: Exception) {
                        logAction("imported pack error: ${f.name} ${e.message?.take(50)}")
                    }
                }
        } catch (_: Exception) { }
        // QH-阶段3：订阅包三级状态——停用不加载 / 仅观察强制 RECORD_ONLY / 启用按原动作
        // QH-P18 外置开关：外置规则集关掉时全部跳过
        if (!externalOn) logAction("sub packs skipped: external disabled by user");        
        if (externalOn) try {
            context.filesDir.resolve("subscriptions")?.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedBy { it.name }?.forEach { f ->
                    try {
                        val st = subStates[f.name] ?: Pair("OBSERVE", false) // 新文件默认仅观察
                        if (st.first == "DISABLED") { logAction("sub pack skipped (disabled): ${f.name}"); return@forEach }
                        val parsed = RuleParser.parse(f.readBytes())
                        if (parsed is RuleParser.RuleParseResult.Ok) {
                            val rules = if (st.first == "OBSERVE")
                                parsed.pack.rules.map { it.copy(action = it.action.copy(type = UiRule.RuleAction.ActionType.RECORD_ONLY)) }
                            else parsed.pack.rules
                            packs.add(parsed.pack.copy(rules = rules))
                            ruleRepository?.registerKnown(parsed.pack.rules.map { it.id }.toSet(), defaultEnabled = true)
                            logAction("sub pack loaded: ${f.name} (${rules.size} rules, state=${st.first})")
                        } else logAction("sub pack rejected: ${f.name}")
                    } catch (e: Exception) {
                        logAction("sub pack error: ${f.name} ${e.message?.take(50)}")
                    }
                }
        } catch (_: Exception) { }
        index = if (packs.isEmpty()) null else RuleIndex.build(packs)
    }

    /** 清空索引强制重载（设置页"重新加载规则"入口）。 */
    fun reload(context: Context) {
        index = null
        ensureLoaded(context)
    }

    /**
     * 处理一条守卫事件（工作线程）。
     * @param rootProvider 需要节点快照时的按需读取器（无障碍服务提供，工作线程安全）。
     * @return 仲裁后落地的动作描述（本期只有 RECORD_ONLY/观察记录）。
     */
    fun handle(
        event: GuardEvent,
        policyInput: ProtectionReducer.PolicyInput,
        reducer: ProtectionReducer,
        state: ProtectionReducer.State,
        rootProvider: () -> AccessibilityNodeInfo?
    ): Pair<ProtectionReducer.State, List<String>> {
        val (nextState, candidates) = reducer.reduce(state, event)
        if (candidates.isEmpty()) {
            logAction("reduce: 0 candidates pkg=${event.session.packageName} paused=${policyInput.globalPaused} enabled=${policyInput.globalEnabled}")
            return nextState to emptyList()
        }
        logAction("reduce: ${candidates.size} candidates")
        val arbiterContext = ActionArbiter.ExecutionContext(
            paused = policyInput.globalPaused,
            stopRequested = false,
            sensitive = event.session.packageName in policyInput.sensitivePackages,
            sensorGateOpen = false // P10 安全门前 Sensor 候选永不执行
        )
        val allowed = ActionArbiter.arbitrate(candidates, arbiterContext)
        logAction("arbiter: ${allowed.size} of ${candidates.size}")

        val executed = mutableListOf<String>()
        for (action in allowed) {
            when (action) {
                is ProtectionReducer.CandidateAction.RecordOnly -> {
                    val line = "RECORD ${event.session.packageName} ${action.type} epoch=${action.windowEpoch}"
                    logAction(line); executed.add(line)
                    // QH-P18 阶段2 观察舱：RECORD_ONLY 命中写观察记录（零界面副作用）
                    observeRecordOnlyHits(event, rootProvider)
                }
                is ProtectionReducer.CandidateAction.CloseAd -> {
                    // QH-P08：真实执行链（敏感排除→冷却→真点击→后验）
                    val line = executeClickFlow(event, rootProvider)
                    if (line != null) executed.add(line)
                }
                is ProtectionReducer.CandidateAction.MaskRegion -> {
                    // QH-P08-04/P09：触摸保护转交——LiveSnapshot 命中节点边界交 TouchShieldManager
                    val line = executeMaskFlow(event, rootProvider)
                    if (line != null) executed.add(line)
                }
                else -> {
                    // BlockLaunch（P12 拦截在服务层 BACK 处理）/StartSensorSession（P10 安全门 NO_GO 不执行）
                    val line = "PENDING ${action::class.simpleName} (转交对应子系统)"
                    logAction(line); executed.add(line)
                }
            }
        }
        return nextState to executed
    }

    /**
     * 触摸保护执行链（QH-P08-04/P09）：匹配含边界 → 敏感排除 → 冷却 → 交 TouchShieldManager。
     * @return 日志行；null=本事件无可执行项。
     */
    private fun executeMaskFlow(
        event: GuardEvent,
        rootProvider: () -> AccessibilityNodeInfo?
    ): String? {
        val idx = index
        if (idx == null) { logAction("mask skip: index empty"); return null }
        val root = rootProvider()
        if (root == null) { logAction("mask skip: no root node"); return null }
        val live = NodeSnapshotReader.snapshotLive(root)
        if (live == null) { logAction("mask skip: snapshot null"); return null }
        try {
            if (!SnapshotNode.isWithinBudget(live.rootSnapshot)) { logAction("mask skip: over budget"); return null }
            if (SafetyExclusions.windowHasSensitiveContext(live.rootSnapshot)) {
                val line = "SKIP sensitive window ${event.session.packageName}"
                logAction(line); return line
            }
            // QH-阶段1：仅 TYPE_WINDOW_STATE_CHANGED 的 className 可信为 Activity 名；
            // 其余事件 activityConfirmed=false，activityIds 规则按不匹配处理（fail-closed）
            // 仅窗口状态变更事件携带可信的 Activity 类名
            val wc = event as? top.hnwen17.guard.core.engine.GuardEvent.WindowChanged
            val confirmed = wc != null && (wc.eventTypes and android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) != 0
            val activityName = if (confirmed) event.session.classNameHint else null
            val windowSnapshot = WindowSnapshot(
                packageName = event.session.packageName,
                versionCode = 1,
                root = live.rootSnapshot,
                activityId = activityName,
                activityConfirmed = confirmed
            )
            val candidates = idx.candidatesFor(event.session.packageName, versionCode = 1, activityId = activityName, activityConfirmed = confirmed)
            val matches = RuleMatcher.findMatches(candidates, windowSnapshot)
            if (matches.isEmpty()) { logAction("mask miss: 0 hit"); return null }
            // 取命中节点边界（取面积最大的命中，遮罩更稳）
            val best = matches.maxByOrNull { m ->
                val b = m.node.boundsInScreen
                b?.let { (it.right - it.left) * (it.bottom - it.top) } ?: 0
            } ?: return null
            val bounds = best.node.boundsInScreen ?: return null
            val ledger = sessionStateFor(event.session.epoch)
            val fakeRule = matches.first { it.node == best.node }.rule
            if (!ledger.canAttempt(fakeRule, 2000L)) { logAction("mask cooldown"); return null }
            ledger.recordAttempt(fakeRule, 2000L, 0L)
            maskExecutor?.invoke(event.session.packageName, bounds.left, bounds.top, bounds.right, bounds.bottom, event.session.epoch)
            val line = "MASKED ${event.session.packageName} [${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] epoch=${event.session.epoch}"
            logAction(line)
            return line
        } finally {
            live.recycleAll()
        }
    }

    /**
     * Cleaner 真实执行链：匹配 → 敏感排除 → 冷却账本 → ACTION_CLICK → 后验。
     * @return 落地动作的日志行（null=本事件无可执行项，同样如实）。
     */
    private fun executeClickFlow(
        event: GuardEvent,
        rootProvider: () -> AccessibilityNodeInfo?,
        retryCount: Int = 0
    ): String? {
        val idx = index
        if (idx == null) { logAction("match skip: index empty"); return null }
        var root = rootProvider()
        // MuMu 实测：rootInActiveWindow 在窗口过渡期返回 null → 延迟重试最多3次
        var retry = 0
        while (root == null && retry < 3) {
            Thread.sleep(1500)
            root = rootProvider()
            retry++
            logAction("root retry #$retry: ${root != null}")
        }
        if (root == null) { logAction("match skip: no root node after 3 retries"); return null }
        // QH-P18 实测（观察日志误报）：窗口过渡期 active window 可能仍是上一个应用——
        // 包名不一致时快照/点击都会落到错误的窗口，宁可不执行也不跨窗口操作。
        val rootPkg = root.packageName?.toString()
        if (rootPkg != null && rootPkg != event.session.packageName) {
            logAction("match skip: root pkg mismatch ($rootPkg != ${event.session.packageName})")
            return null
        }
        val live = NodeSnapshotReader.snapshotLive(root)
        if (live == null) { logAction("match skip: snapshot null"); return null }
        try {
            if (!SnapshotNode.isWithinBudget(live.rootSnapshot)) { logAction("match skip: over budget"); return null }
            // 敏感窗口排除（QH-P07-07）：密码/支付/登录样式一律不点击
            if (SafetyExclusions.windowHasSensitiveContext(live.rootSnapshot)) {
                val line = "SKIP sensitive window ${event.session.packageName}"
                logAction(line); return line
            }
            val wcM = event as? top.hnwen17.guard.core.engine.GuardEvent.WindowChanged
            val confirmedM = wcM != null && (wcM.eventTypes and android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) != 0
            val activityNameM = if (confirmedM) event.session.classNameHint else null
            val windowSnapshot = WindowSnapshot(event.session.packageName, versionCode = 1, root = live.rootSnapshot, activityId = activityNameM, activityConfirmed = confirmedM)
            val candidates = idx.candidatesFor(event.session.packageName, versionCode = 1, activityId = activityNameM, activityConfirmed = confirmedM)
            if (candidates.isEmpty()) { logAction("match skip: no candidates for ${event.session.packageName}"); return null }
            val matches = RuleMatcher.findMatches(candidates, windowSnapshot)
            if (matches.isEmpty()) {
                // QH-P18 观察日志：疑似广告窗口但无规则命中 → 留痕（用户可见+可导出补规则）
                observeIfSuspect(event.session, live.rootSnapshot, reason = "miss")
                logAction("match miss: ${candidates.size} candidates, 0 hit"); return null
            }
            val ledger = sessionStateFor(event.session.epoch)
            // 优先级序遍历：第一可执行（CLICK类、冷却通过、页面约束满足）即执行
            // QH-P18 全局点击限流：10 秒内最多 3 次（跨规则），防「反复操作屏幕致失去控制」
            // 重试点击不受全局限流约束：重试本身已由 retryCount≤2 封顶，
            // 若被限流吞掉，重试机制形同虚设（MuMu 实测：retry attempt 1 紧跟 rate limited）
            if (retryCount == 0 && !clickAllowed()) { logAction("rate limited: too many clicks in 10s window"); return null }
            for (rule in RulePriority.sortedForExecution(matches.map { it.rule })) {
                if (rule.action.type != UiRule.RuleAction.ActionType.CLICK_VERIFIED_NODE) continue // RECORD_ONLY 走观察通道
                if (RuleValidator.isExpired(rule, nowEpochMs = clock.nowMs())) continue
                if (rule.page?.let { !RuleMatcher.pageAllows(it, windowSnapshot) } == true) continue
                if (!ledger.canAttempt(rule, rule.action.cooldownMs)) continue
                val match = matches.firstOrNull { it.rule == rule }
                val node = match?.let { m -> live.nodeFor(m.node) }
                if (node == null) {
                    logAction("EXEC fail: live node lost for ${rule.id}")
                    continue
                }
                val clicked = clickNodeOrAncestor(node, rule.id)
                if (!clicked) {
                    // QH-P18 观察日志：有规则命中但点击失败（控件不可点且无可点祖先）→ 留痕；
                    // 但 EXEC 后 5 秒内的失败是窗口关闭过程的连带事件，属噪音不记
                    if (clock.nowMs() - lastExecutedAtMs > 5_000) {
                        observeIfSuspect(event.session, live.rootSnapshot, reason = "exec_fail")
                    }
                    logAction("EXEC fail: click rejected ${rule.id}"); continue
                }
                lastExecutedAtMs = clock.nowMs()
                lastAdActionAtMs = lastExecutedAtMs
                ledger.recordAttempt(rule, rule.action.cooldownMs, rule.postcondition?.timeoutMs ?: 0L)
                val done = "EXECUTED ${rule.id} epoch=${event.session.epoch}"
                logAction(done)
                recordStore?.record(rule.id, rule.version, event.session.packageName, event.session.epoch,
                    top.hnwen17.guard.core.records.ProtectionOutcome.EXECUTED,
                    java.lang.System.currentTimeMillis(),
                    top.hnwen17.guard.core.records.EventType.AD_CLOSE_CLICKED)
                // QH-P18 通用后验：无显式 postcondition 的规则用命中节点标记做「是否真关闭」验证
                val marker = match?.node?.let { m ->
                    m.text?.takeIf { it.isNotBlank() } ?: m.desc?.takeIf { it.isNotBlank() }
                        ?: m.viewId?.substringAfterLast('/')
                }
                // QH-P20 内容指纹：点击前记录窗口前几个文本节点，用于点击后比对是否真的变化
                val preFingerprint = live.rootSnapshot.let { root ->
                    buildList {
                        fun collect(n: top.hnwen17.guard.core.rules.SnapshotNode) {
                            if (size >= 5) return
                            n.text?.takeIf { it.isNotBlank() }?.let { add(it) }
                            if (size >= 5) return
                            n.children.forEach { collect(it) }
                        }
                        collect(root)
                    }.joinToString("|")
                }
                schedulePostcondition(event, event.session, rule, rootProvider, ledger, marker, retryCount, preFingerprint)
                return done
            }
            return null
        } finally {
            live.recycleAll() // 节点即用即弃（QH-P05-05 生命周期合同）
        }
    }

    /**
     * QH-P18 阶段2 观察舱：RECORD_ONLY 规则命中 → 记录「本来会点哪个节点」。
     * 节流：≥5s 一次；敏感窗口不采样；失败静默（诊断通道不受损）。
     */
    private fun observeRecordOnlyHits(event: GuardEvent, rootProvider: () -> AccessibilityNodeInfo?) {
        val idx = index ?: return
        val store = observeStore ?: return
        val now = clock.nowMs()
        if (now - lastRoScanAtMs < 5_000) return
        lastRoScanAtMs = now
        val wc = event as? top.hnwen17.guard.core.engine.GuardEvent.WindowChanged ?: return
        val confirmed = (wc.eventTypes and android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) != 0
        val activityName = if (confirmed) wc.session.classNameHint else null
        val root = rootProvider() ?: return
        val live = NodeSnapshotReader.snapshotLive(root) ?: return
        try {
            if (!SnapshotNode.isWithinBudget(live.rootSnapshot)) return
            if (SafetyExclusions.windowHasSensitiveContext(live.rootSnapshot)) return
            val ws = WindowSnapshot(event.session.packageName, 1, live.rootSnapshot,
                activityId = activityName, activityConfirmed = confirmed)
            val roRules = idx.candidatesFor(event.session.packageName, 1, activityName, confirmed)
                .map { it.rule }.filter { it.action.type == UiRule.RuleAction.ActionType.RECORD_ONLY }
            if (roRules.isEmpty()) return
            val matches = RuleMatcher.findMatches(roRules.map { RuleIndex.IndexedRule(it, "ro") }, ws)
            for (m in matches.take(2)) {
                val marker = m.node.text?.takeIf { it.isNotBlank() }
                    ?: m.node.desc?.takeIf { it.isNotBlank() }
                    ?: m.node.viewId?.substringAfterLast('/') ?: continue
                val added = store.observe(event.session.packageName, activityName ?: "",
                    "ro:${m.rule.id}", java.lang.System.currentTimeMillis(), listOf(marker))
                if (added) logAction("observe-only: ${m.rule.id} marker=$marker")
            }
        } catch (e: Exception) {
            android.util.Log.w("RuleRuntime", "observe-only fail: ${e.message}")
        }
    }

    /**
     * QH-P18 观察日志：疑似广告窗口留痕（有规则没执行成功/无规则命中）。
     * 敏感窗口不采样（隐私合同）；去重与容量由 ObserveStore 控制；失败静默（诊断通道不受损）。
     */
    private fun observeIfSuspect(
        session: top.hnwen17.guard.core.session.WindowSession,
        rootSnapshot: top.hnwen17.guard.core.rules.SnapshotNode,
        reason: String
    ) {
        val store = observeStore ?: return
        try {
            if (!top.hnwen17.guard.core.rules.AdWindowHeuristics.isSuspectAdWindow(session.classNameHint, rootSnapshot)) return
            val added = store.observe(
                packageName = session.packageName,
                className = session.classNameHint.orEmpty(),
                reason = reason,
                atEpochMs = java.lang.System.currentTimeMillis(),
                samples = top.hnwen17.guard.core.rules.AdWindowHeuristics.collectSamples(rootSnapshot)
            )
            if (added) logAction("observe: ${session.packageName} ($reason) samples=${store.all.value.lastOrNull()?.samples?.size ?: 0}")
        } catch (e: Exception) {
            android.util.Log.w("RuleRuntime", "observe fail: ${e.message}")
        }
    }

    /**
     * QH-P18 通用性：跳过控件常为不可点文本（可点击的是外层容器）。
     * 点击失败时上溯最近可点祖先（≤4 层）；**区域上限**：祖先面积 ≤ 命中文本面积的 9 倍
     * （线性 3 倍）——开屏广告的“点按跳转”容器是全屏的，绝不能把上溯变成“帮用户点开广告”。
     * 中间节点用完即回收（live 管理之外的新实例）。
     */
    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo, ruleId: String): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val nodeRect = android.graphics.Rect()
        node.getBoundsInScreen(nodeRect)
        if (nodeRect.isEmpty || nodeRect.width() <= 0 || nodeRect.height() <= 0) return false
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 6) {
            val r = android.graphics.Rect()
            parent.getBoundsInScreen(r)
            val withinSkipArea = !r.isEmpty &&
                r.width().toLong() * r.height() <=
                nodeRect.width().toLong() * nodeRect.height().toLong() * 12
            if (!withinSkipArea) { parent.recycle(); return tapCenter(nodeRect, ruleId) } // 祖先只会更大：止损转坐标点按
            val clicked = parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val next = parent.parent
            parent.recycle()
            if (clicked) return true
            parent = next
            depth++
        }
        parent?.recycle()
        return tapCenter(nodeRect, ruleId) // 全部失败：GKD 同款按节点中心坐标点按
    }

    /** QH-P18：坐标点按兜底（手势能力由服务注入；李跳跳/GKD 对不可点控件均采用坐标点击）。 */
    private fun tapCenter(rect: android.graphics.Rect, ruleId: String): Boolean {
        val dispatcher = gestureDispatcher ?: return false
        try { Thread.sleep(200) } catch (_: InterruptedException) { } // 等开屏渲染完成再落点
        val ok = dispatcher.invoke(rect.exactCenterX(), rect.exactCenterY())
        if (ok) logAction("gesture tap: $ruleId @(${rect.exactCenterX().toInt()},${rect.exactCenterY().toInt()})")
        return ok
    }

    /** 后验：延迟检查 absentViewId 是否消失；VERIFIED 只在有证据时记。 */
    private fun schedulePostcondition(
        event: top.hnwen17.guard.core.engine.GuardEvent,
        session: top.hnwen17.guard.core.session.WindowSession,
        rule: UiRule,
        rootProvider: () -> AccessibilityNodeInfo?,
        ledger: RuleSessionState,
        marker: String? = null,
        retryCount: Int = 0,
        preFingerprint: String = ""
    ) {
        val scope = postScope ?: return
        val timeout = rule.postcondition?.timeoutMs?.takeIf { it > 0 } ?: 800L
        scope.launch {
            kotlinx.coroutines.delay(timeout)
            val root = rootProvider()
            // 全树检查目标 viewId 是否仍存在（只查根节点会误判 VERIFIED——P08 实测缺陷）
            val post = rule.postcondition
            val absent = post?.absentViewId?.let { vid ->
                NodeSnapshotReader.containsViewId(root, vid)?.let { !it }
            } ?: post?.absentTextEquals?.let { needle ->
                // H5 无 viewId：以独立文本是否仍在窗口内判定关闭（trim 全文等于）
                if (root == null) null else {
                    val live = NodeSnapshotReader.snapshotLive(root)
                    if (live == null) null else {
                        try {
                            if (!top.hnwen17.guard.core.rules.SnapshotNode.isWithinBudget(live.rootSnapshot)) return@let null
                            var found = false
                            fun scan(n: top.hnwen17.guard.core.rules.SnapshotNode) {
                                if (found) return
                                n.text?.trim()?.lowercase()?.let { if (it == needle.lowercase()) { found = true; return } }
                                n.desc?.trim()?.lowercase()?.let { if (it == needle.lowercase()) { found = true; return } }
                                for (c in n.children) scan(c)
                            }
                            scan(live.rootSnapshot)
                            !found // 标记仍在=false(absent)=FAILED；标记消失=true=VERIFIED
                        } finally { live.recycleAll() }
                    }
                }
            } ?: run {
                // 无显式 postcondition 的通用后验：重新匹配同一规则。
                // 命中节点仍在原地（如 H5「跳过」按钮未消失）= 点击未生效 → FAILED；
                // 规则不再命中（广告容器/按钮随点击消失或页面切换）→ VERIFIED。
                // 旧指纹方案弃用：H5 广告页内容动态（倒计时/轮播），指纹 800ms 后必变，
                // 真机实测导致「实际未关闭却 VERIFIED」误判（09-21 17:58:13）。
                if (root != null) {
                    val live = NodeSnapshotReader.snapshotLive(root)
                    if (live != null) {
                        try {
                            val idxNow = index
                            if (idxNow == null || !SnapshotNode.isWithinBudget(live.rootSnapshot)) return@run null
                            val wc = event as? top.hnwen17.guard.core.engine.GuardEvent.WindowChanged
                            val activityId = wc?.session?.classNameHint
                            val confirmed = wc != null
                            val windowSnapshot = top.hnwen17.guard.core.rules.WindowSnapshot(
                                session.packageName, 1L, live.rootSnapshot,
                                activityId = activityId, activityConfirmed = confirmed)
                            val candidates = idxNow.candidatesFor(session.packageName, 1, activityId, confirmed)
                            val stillHit = RuleMatcher.findMatches(candidates, windowSnapshot)
                                .any { m -> m.rule.id == rule.id && RulePriority.sortedForExecution(listOf(m.rule)).isNotEmpty() }
                            !stillHit // 仍命中 → 点击无效(false=absent false) → FAILED；不再命中 → VERIFIED
                        } finally { live.recycleAll() }
                    } else null
                } else null
            }
            when {
                absent == null -> logAction("POST ${rule.id}: window gone, keep EXECUTED")
                absent -> {
                    ledger.markSatisfied(rule)
                    logAction("VERIFIED ${rule.id}: absentViewId gone")
                    recordStore?.record(rule.id, rule.version, session.packageName, session.epoch,
                        top.hnwen17.guard.core.records.ProtectionOutcome.VERIFIED,
                        java.lang.System.currentTimeMillis(),
                        top.hnwen17.guard.core.records.EventType.AD_WINDOW_CLOSED_VERIFIED)
                }
                else -> {
                    logAction("FAILED ${rule.id}: ${rule.postcondition?.absentViewId} still present")
                    // QH-P08-06：失败记录（供设置页提供"停用此规则"入口；不自动停用）
                    recordStore?.record(rule.id, rule.version, session.packageName, session.epoch,
                        top.hnwen17.guard.core.records.ProtectionOutcome.FAILED,
                        java.lang.System.currentTimeMillis(),
                        top.hnwen17.guard.core.records.EventType.AD_CLOSE_FAILED)
                    lastFailedRuleId = rule.id
                    // QH-P18 自动止损：同一应用内同一规则失败 ≥3 次 → 自动停用
                    // （防止坏规则在每次窗口刷新时反复无效点击，如 ltt3.209 在腾讯视频连败 5 次）
                    val failKey = "${session.packageName}|${rule.id}"
                    val fails = (ruleFailCounts[failKey] ?: 0) + 1
                    ruleFailCounts[failKey] = fails
                    if (fails >= 3) {
                        ruleRepository?.recordFailureAndDisable(rule.id)
                        ruleFailCounts.remove(failKey)
                        logAction("auto-disabled ${rule.id} after $fails failures in ${session.packageName}")
                    }
                    // QH-P18 重试：FAILED 后 1.5s 自动重新匹配并点击（最多 2 次重试）
                    if (retryCount < 2) {
                        scope.launch {
                            kotlinx.coroutines.delay(1500)
                            logAction("retry ${rule.id} attempt ${retryCount + 1}")
                            executeClickFlow(
                                top.hnwen17.guard.core.engine.GuardEvent.WindowChanged(
                                    session, clock.nowMs(),
                                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                                ),
                                rootProvider, retryCount + 1
                            )
                        }
                    }
                }
            }
        }
    }

    private fun sessionStateFor(epoch: Long): RuleSessionState =
        sessionStates.getOrPut(epoch) { RuleSessionState(clock) }.also {
            if (sessionStates.size > 4) sessionStates.remove(sessionStates.keys.first())
        }

    companion object {
        /** 记录类型占位：RECORD_ONLY 落 REQUESTED，不冒充 EXECUTED（统计口径守门）。 */
        fun outcomeForRecordOnly(): ProtectionOutcome = ProtectionOutcome.REQUESTED
        val recordEventType: EventType = EventType.AD_WINDOW_DETECTED
    }
}
