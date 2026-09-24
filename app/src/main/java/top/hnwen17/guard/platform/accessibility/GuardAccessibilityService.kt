package top.hnwen17.guard.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.hnwen17.guard.core.engine.GuardEvent
import top.hnwen17.guard.core.engine.ProtectionReducer
import top.hnwen17.guard.core.session.WindowSession
import top.hnwen17.guard.platform.CapabilityRepository
import top.hnwen17.guard.platform.EngineDispatcher
import top.hnwen17.guard.platform.RuleRuntime

/**
 * 无障碍服务：廉价事件入口（QH-P05-03/04）→ 规则运行时（QH-P07 联调）。
 *
 * 回调硬约束（AGENTS/技术合同）：
 * - onAccessibilityEvent 运行在主线程，**只做 primitive 复制与廉价筛选**；
 * - 规则计算/节点快照全部在 EngineDispatcher 的工作线程执行；
 * - 保留窗口切换事件语义：撤销时机不因按包过滤而丢失。
 *
 * 生命周期：onServiceConnected/onInterrupt/onUnbind/onDestroy 写入 CapabilityRepository——
 * 系统设置"已开启"≠ Binder 正在工作，UI 只认真实回调。
 */
class GuardAccessibilityService : AccessibilityService() {

    private companion object {
        const val CHANNEL_ID = "guard_keepalive"
        const val NOTIFICATION_ID = 1001
    }

    private var dispatcher: EngineDispatcher? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtime by lazy { (application as top.hnwen17.guard.GuardApplication).ruleRuntime } // 共享单例
    private var reducer: ProtectionReducer? = null
    private var reducerState = ProtectionReducer.State()
    private val windowEpochs = HashMap<Int, Long>() // windowId → 稳定 epoch（有界）
    @Volatile private var lastForegroundPackage: String? = null
    private val touchShield by lazy {
        top.hnwen17.guard.platform.touch.TouchShieldManager(this) { visible, reason ->
            android.util.Log.d("RuleRuntime", "shield $visible: $reason")
        }
    } // QH-P09-01：TYPE_ACCESSIBILITY_OVERLAY 遮罩管理

    /** QH-P20 统一活动性检查：内置+外置任一开启即视为防护活跃；全关则一切自动行为静默。 */
    private fun protectionActive(): Boolean {
        val app = applicationContext as? top.hnwen17.guard.GuardApplication ?: return false
        return app.subscriptionsStore.builtinEnabled.value || app.subscriptionsStore.externalEnabled.value
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        setUpEngine("onServiceConnected")
        CapabilityRepository.onAccessibilityBound("binder connected")
    }

    /** QH-P08 实测：onRebind（解绑后重绑）不会重复调用 onServiceConnected，必须在此恢复完整状态。 */
    override fun onRebind(intent: android.content.Intent?) {
        super.onRebind(intent)
        setUpEngine("onRebind")
        CapabilityRepository.onAccessibilityBound("binder reconnected")
    }

    private fun setUpEngine(tag: String) {
        val app = applicationContext as? top.hnwen17.guard.GuardApplication
        runtime.ensureLoaded(this) // release 构建无 fixture 资产 → 空索引（如实无动作）
        startKeepalive(tag) // QH-P17：前台常驻，降低激进 ROM 杀进程概率
        // QH-P08-04/P09：遮罩执行注册（TouchShieldManager 内部 post 主线程）
        // QH-P18：坐标点按兜底注入（dispatchGesture 需主线程调用，post 后乐观返回）
        runtime.gestureDispatcher = { x, y ->
            mainHandler.post {
                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
                    android.graphics.Path().apply { moveTo(x, y) }, 0L, 60L
                )
                dispatchGesture(
                    android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(stroke).build(), null, null
                )
            }
            true
        }
        runtime.maskExecutor = { pkg, l, t, r, b, epoch ->
            touchShield.show(
                top.hnwen17.guard.core.session.WindowSession(0, pkg, 0, epoch),
                top.hnwen17.guard.platform.touch.ShieldBoundsResolver.Rect(l, t, r, b)
            ) { }
            // QH-P18：遮罩展示计入 TOUCH 能力记录（同会话去重由 RecordStore 负责）
            (applicationContext as? top.hnwen17.guard.GuardApplication)?.recordStore?.record(
                ruleId = "touch.shield.mask", ruleVersion = 1, packageName = pkg,
                windowEpoch = epoch, outcome = top.hnwen17.guard.core.records.ProtectionOutcome.EXECUTED,
                atEpochMs = java.lang.System.currentTimeMillis(),
                eventType = top.hnwen17.guard.core.records.EventType.RISK_REGION_MASKED
            )
        }
        val policyInput = app?.repository?.let { repo ->
            val settings = repo.state.value.settings
            ProtectionReducer.PolicyInput(globalPaused = settings.paused, globalEnabled = settings.enabled,
                appTri = settings.policies.mapValues { it.value.tri }, legacyPolicies = settings.policies)
        } ?: ProtectionReducer.PolicyInput(globalPaused = false, globalEnabled = CapabilitySetAll())
        reducer = ProtectionReducer(policyInput, Monotonic())
        // QH-P18 关键修复：reducer 持有的是构造时策略快照——订阅设置流，变更即重建，
        // 否则首页/应用页开关在服务启动后永远不生效
        app?.let { a ->
            serviceScope.launch {
                a.repository.state.collect { state ->
                    reducer = ProtectionReducer(
                        ProtectionReducer.PolicyInput(
                            globalPaused = state.settings.paused,
                            globalEnabled = state.settings.enabled,
                            appTri = state.settings.policies.mapValues { it.value.tri },
                            legacyPolicies = state.settings.policies
                        ), Monotonic()
                    )
                }
            }
        }
        dispatcher?.shutdown()
        val dispatcher = EngineDispatcher(
            scope = serviceScope,
            workerContext = Dispatchers.Default,
            onEvent = { event -> handleEvent(event) }
        )
        dispatcher.start()
        this.dispatcher = dispatcher
        android.util.Log.d("RuleRuntime", "engine set up ($tag): generic rules active")
    }

    /**
     * QH-P17 存活加固：specialUse 前台服务 + 常驻通知。
     * 系统绑定的无障碍服务进程仍可能被激进 ROM 回收；前台优先级显著降低该概率。
     * Android 12+ 对后台启动 FGS 有限制：绑定回调通常豁免，但仍 try-catch 兜底
     * （失败只损失存活加固，不影响引擎功能，绝不崩服务进程）。
     */
    private fun startKeepalive(tag: String) {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL_ID, "防护运行状态", android.app.NotificationManager.IMPORTANCE_MIN)
            )
            val intent = android.content.Intent(this, top.hnwen17.guard.MainActivity::class.java)
            val pending = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val note = android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(applicationInfo.icon)
                .setContentTitle("轻护防护运行中")
                .setContentText("正在监测广告窗口 · 点按查看状态")
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
            startForegroundCompat(NOTIFICATION_ID, note)
            android.util.Log.d("RuleRuntime", "keepalive foreground ($tag)")
        } catch (e: Exception) {
            android.util.Log.w("RuleRuntime", "keepalive unavailable ($tag): ${e.message}")
        }
    }

    /** API 34+ 强制声明类型 specialUse；旧版本用双参（未知类型位在旧平台会抛 IllegalArgumentException）。 */
    private fun startForegroundCompat(id: Int, note: android.app.Notification) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(id, note, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            @Suppress("DEPRECATION") startForeground(id, note)
        }
    }

    private fun stopKeepalive() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
    }

    private fun handleEvent(event: GuardEvent) {
        android.util.Log.d("RuleRuntime", "event in: ${event::class.simpleName} pkg=${event.session.packageName}")
        val reducer = reducer ?: return
        val app = applicationContext as? top.hnwen17.guard.GuardApplication ?: return
        val settings = app.repository.state.value.settings
        // QH-P18 关键修复：把应用级策略（应用页每个 App 的三态开关）传入引擎——
        // 此前未传入，应用页「关闭」形同虚设，规则引擎照常点击
        val input = ProtectionReducer.PolicyInput(
            globalPaused = settings.paused,
            globalEnabled = settings.enabled,
            appTri = settings.policies.mapValues { it.value.tri },
            legacyPolicies = settings.policies
        )
        // root 双通道：active window 通道在 MuMu 上会退化返回 null → 回退遍历 windows
        val result = runtime.handle(event, input, reducer, reducerState) {
            rootInActiveWindow ?: windows.orEmpty().firstNotNullOfOrNull { it.root }
        }
        reducerState = result.first
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val dispatcher = dispatcher ?: return
        val e = event ?: return
        val source = e.packageName?.toString() ?: return
        // 廉价筛选：只复制 primitive；自身与系统 UI 事件忽略
        if (source == packageName || source.startsWith("com.android.systemui")) return
        // MuMu 实测：部分事件 windowId 为 null——不丢弃，用合成键保持事件流（丢弃会静默断链）
        val windowId = e.windowId ?: -1
        // 稳定会话身份：同一 (pkg,windowId) 共享 epoch（首个事件单调时钟铸造）；
        // 修正 P08 实测缺陷：epoch 取 eventTime 会让每个事件都成"新会话"，冷却账本被绕过（3522 次点击风暴）。
        val epoch = synchronized(windowEpochs) {
            windowEpochs.getOrPut(windowId) { android.os.SystemClock.uptimeMillis() }
                .also { if (windowEpochs.size > 32) windowEpochs.remove(windowEpochs.keys.first()) }
        }
        val session = WindowSession(
            userId = 0,
            packageName = source,
            windowId = windowId,
            epoch = epoch,
            classNameHint = e.className?.toString().orEmpty().take(GuardEvent.MAX_SUMMARY)
        )
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // QH-P20 事件时捕获：窗口状态变更到达时立即记录（不等待异步处理），
            // 确保每个广告窗口都有痕迹——解决「有广告但零记录」的用户反馈
            val cls = e.className?.toString()?.lowercase().orEmpty()
            if (top.hnwen17.guard.core.rules.AdWindowHeuristics.AD_ACTIVITY_TOKENS.any { cls.contains(it) }) {
                (applicationContext as? top.hnwen17.guard.GuardApplication)?.observeStore?.observe(
                    packageName = source,
                    className = e.className?.toString() ?: "",
                    reason = "event_detected",
                    atEpochMs = java.lang.System.currentTimeMillis(),
                    samples = listOf(e.className?.toString() ?: "")
                )
            }
            // QH-P12：前台切换 → 跳转判定（BACK 拦截或放行），再喂规则引擎
            // MuMu 系统窗口（com.unian.* / com.mumu.*）不参与前台跟踪（会打断驻留计时）
            val isSystemWindow = source.startsWith("com.unian") || source.startsWith("com.mumu") ||
                source.startsWith("com.android.systemui") // 通知栏/系统分享面板，非跳转目标
            val prev = lastForegroundPackage
            if (prev != null && prev != source && !isSystemWindow) onWindowJumpCandidate(prev, source, e.className?.toString().orEmpty())
            if (!isSystemWindow) lastForegroundPackage = source
            touchShield.onWindowChanged(session) // QH-P09-06：窗口切换先撤旧盾
            // QH-P10 实用路径：广告 SDK 全屏 Activity（含摇一摇落地页）→ BACK（无需 Shizuku）
            // QH-P18 开关接线：全局暂停/全局 SENSOR 关/应用级 SENSOR 关 均不再自动返回
            val sensorAllowed = (applicationContext as? top.hnwen17.guard.GuardApplication)?.let { a ->
                val s = a.repository.state.value.settings
                top.hnwen17.guard.core.policy.PolicyResolver.resolve(
                    globalPaused = s.paused, globalEnabled = s.enabled, appId = source,
                    appLegacy = s.policies[source], appTri = s.policies[source]?.tri ?: emptyMap()
                ).sensor
            } ?: false
            if (sensorAllowed && sensorAdBack.shouldBack(e.className?.toString(), monotonicMs())) {
                mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
                android.util.Log.d("RuleRuntime", "SENSOR-AD back: ${e.className}")
                // QH-P18：计入 SENSOR 能力记录（用户要求摇一摇防线可见）
                (applicationContext as? top.hnwen17.guard.GuardApplication)?.recordStore?.record(
                    ruleId = "sensor.ad.back", ruleVersion = 1, packageName = source,
                    windowEpoch = epoch, outcome = top.hnwen17.guard.core.records.ProtectionOutcome.EXECUTED,
                    atEpochMs = java.lang.System.currentTimeMillis(),
                    eventType = top.hnwen17.guard.core.records.EventType.SENSOR_AD_BACKED
                )
            }
        }
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                dispatcher.submit(GuardEvent.WindowChanged(session, monotonicMs(), e.eventType))
        }
    }

    override fun onInterrupt() {
        touchShield.dismiss("interrupted") // QH-P09-06：异常先撤盾
        CapabilityRepository.onAccessibilityInterrupted("interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        touchShield.dismiss("service unbound") // QH-P09-06：解绑先撤盾
        stopKeepalive()
        dispatcher?.shutdown()
        dispatcher = null
        CapabilityRepository.onAccessibilityUnbound("service unbound")
        return super.onUnbind(intent)
    }


    override fun onDestroy() {
        stopKeepalive()
        dispatcher?.shutdown()
        dispatcher = null
        serviceScope.cancel()
        CapabilityRepository.onAccessibilityUnbound("service destroyed")
        super.onDestroy()
    }

    // QH-P12/P13：跳转拦截（BACK 方案，无需手势权限；判定纯逻辑已单测）
    private val sensorAdBack = top.hnwen17.guard.platform.sensor.SensorAdBackGuard(
        clock = top.hnwen17.guard.core.session.MonotonicClock { android.os.SystemClock.uptimeMillis() }
    )
    private val lastBlockAtMs = HashMap<String, Long>() // from|to → 最近拦截时刻（防振荡）

    private val jumpInterceptor = top.hnwen17.guard.platform.jump.JumpInterceptor(
        clock = top.hnwen17.guard.core.session.MonotonicClock { android.os.SystemClock.uptimeMillis() }
    )

    /** 跳转候选：前台包从 X 切到 P 且 P 不同于 X 时判定。 */
    private fun onWindowJumpCandidate(fromPackage: String?, toPackage: String, toClassName: String) {
        fromPackage ?: return
        if (toPackage == fromPackage) return
        val app = applicationContext as? top.hnwen17.guard.GuardApplication ?: return
        val settings = app.repository.state.value.settings
        val targetSensitive = top.hnwen17.guard.core.policy.SafetyExclusions.isSensitivePackageName(toPackage)
        // 来源识别三信号（用户指导：所有需要禁掉的跳转都来自广告）
        val now = monotonicMs()
        val engineClickRecent = app.ruleRuntime.recentAdAction(withinMs = 3000, nowMs = now)
        // 「广告在场」只认引擎真实尝试过且未成功关闭的记录（FAILED/EXECUTED）。
        // 观察日志（疑似留痕）严禁作为证据：浏览器等信息流页永远有 miss 记录，
        // 弱证据会自激振荡——打开 App→记疑似→一切跳转被判广告→BACK→再记→持续误拦
        val adStillVisible = app.recordStore.all.value.any {
            it.packageName == fromPackage && it.atEpochMs >= now - 5000 &&
                (it.outcome == top.hnwen17.guard.core.records.ProtectionOutcome.FAILED ||
                 it.outcome == top.hnwen17.guard.core.records.ProtectionOutcome.EXECUTED)
        }
        val landingPage = top.hnwen17.guard.core.rules.AdWindowHeuristics.AD_ACTIVITY_TOKENS.any {
            toClassName.lowercase().contains(it)
        }
        val adOrigin = engineClickRecent || adStillVisible || landingPage
        // 同目标冷却：10 秒内同一对 from→to 只拦一次（防 BACK 振荡，真机 09-24 教训）
        if (lastBlockAtMs["$fromPackage|$toPackage"]?.let { now - it < 10_000L } == true) return
        val decision = jumpInterceptor.decide(
            fromPackage = fromPackage,
            toPackage = toPackage,
            jumpEnabled = run {
                val resolved = top.hnwen17.guard.core.policy.PolicyResolver.resolve(
                    globalPaused = settings.paused,
                    globalEnabled = settings.enabled,
                    appId = fromPackage,
                    appLegacy = settings.policies[fromPackage]
                )
                resolved.jump
            },
            targetSensitive = targetSensitive,
            adOrigin = adOrigin
        )
        android.util.Log.d("RuleRuntime", "JUMP ${decision.action} $fromPackage -> $toPackage (${decision.reason})")
        if (decision.action == top.hnwen17.guard.platform.jump.JumpInterceptor.Action.BLOCK_BACK) {
            lastBlockAtMs["$fromPackage|$toPackage"] = monotonicMs()
            mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
            // QH-P13：拦截执行落记录（START_REJECTED 为真实执行动作，计入统计）
            (applicationContext as? top.hnwen17.guard.GuardApplication)?.recordStore?.record(
                ruleId = "jump.auto.back", ruleVersion = 1, packageName = fromPackage,
                windowEpoch = monotonicMs(),
                outcome = top.hnwen17.guard.core.records.ProtectionOutcome.START_REJECTED,
                atEpochMs = System.currentTimeMillis(),
                eventType = top.hnwen17.guard.core.records.EventType.LAUNCH_INTENT_OBSERVED
            )
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun monotonicMs(): Long = android.os.SystemClock.uptimeMillis()

    private fun CapabilitySetAll() = top.hnwen17.guard.core.Capability.entries.toSet()

    /** 单调时钟注入点（测试同款实现）。 */
    private class Monotonic : top.hnwen17.guard.core.session.MonotonicClock {
        override fun nowMs(): Long = android.os.SystemClock.uptimeMillis()
    }
}
