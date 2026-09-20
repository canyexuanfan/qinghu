package top.hnwen17.guard

import android.app.Application
import top.hnwen17.guard.data.*
import kotlinx.coroutines.launch
import top.hnwen17.guard.ui.AppIconLoader

class GuardApplication : Application() {
    var previewAcknowledged: Boolean = false

    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    val ruleRepository by lazy { top.hnwen17.guard.data.RuleRepository(this, appScope) } // QH-P08-05/06
    val recordStore by lazy { top.hnwen17.guard.data.records.RecordStore() } // QH-P08-07
    val observeStore by lazy { top.hnwen17.guard.data.records.ObserveStore() } // QH-P18 观察日志
    val subscriptionsStore by lazy { top.hnwen17.guard.data.SubscriptionsStore() } // 阶段3 规则订阅
    val updateSettings by lazy { top.hnwen17.guard.data.UpdateSettings(this, appScope) } // QH-P16
    val ruleRuntime by lazy { // QH-P07/P08：规则运行时（服务与设置页共享单例）
        top.hnwen17.guard.platform.RuleRuntime(
            postScope = appScope,
            ruleRepository = ruleRepository,
            recordStore = recordStore,
            observeStore = observeStore
        )
    }

    override fun onCreate() {
        super.onCreate()
        top.hnwen17.guard.platform.shizuku.ShizukuBridge.attach(this) // QH-P05-07/08
        // QH-P08-07：防护记录持久化（启动恢复+变更落盘，应用私有目录）
        top.hnwen17.guard.data.records.RecordStore.load(this, recordStore)
        appScope.launch {
            recordStore.all.collect {
                top.hnwen17.guard.data.records.RecordStore.save(this@GuardApplication, recordStore)
            }
        }
        appScope.launch {
            subscriptionsStore.all.collect {
                top.hnwen17.guard.data.SubscriptionsStore.save(this@GuardApplication, subscriptionsStore)
                if (it.any { e -> e.id != "builtin" }) ruleRuntime.reload(this@GuardApplication) // 三级状态变更即时重建索引
            }
        }
        appScope.launch {
            subscriptionsStore.builtinEnabled.collect {
                ruleRuntime.reload(this@GuardApplication) // 内置开关变更即时重建索引
            }
        }
        // QH-P18：观察日志持久化（出现广告必留痕，用户可导出反馈补规则）
        top.hnwen17.guard.data.records.ObserveStore.load(this, observeStore)
        // 阶段3：规则订阅持久化 + 变更即重建规则索引（三级状态/内置开关即时生效）
        top.hnwen17.guard.data.SubscriptionsStore.load(this, subscriptionsStore)
        appScope.launch {
            observeStore.all.collect {
                top.hnwen17.guard.data.records.ObserveStore.save(this@GuardApplication, observeStore)
            }
        }
    }
    val repository by lazy { GuardRepository(this, SourceFactory.create(), recordStore, observeStore) }
    private val iconsLazy = lazy { AppIconLoader(this) }
    val icons get() = iconsLazy.value
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN && iconsLazy.isInitialized()) icons.clear()
    }
}
