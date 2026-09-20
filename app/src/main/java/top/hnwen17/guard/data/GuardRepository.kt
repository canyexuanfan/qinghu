package top.hnwen17.guard.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import top.hnwen17.guard.core.*
import java.io.IOException

/** One bounded mutation queue. No timer, polling, service, or Activity reference. */
class GuardRepository(
    context: Context,
    private val source: FrontendSource,
    private val recordStore: top.hnwen17.guard.data.records.RecordStore? = null,
    private val observeStore: top.hnwen17.guard.data.records.ObserveStore? = null
) {
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
        application.preferencesDataStoreFile("guard-ui")
    }
    private val settingsKey = stringPreferencesKey("settings")
    private val clearedKey = booleanPreferencesKey("records_cleared")
    private val mutations = Channel<suspend () -> Unit>(capacity = 32)
    private val _state = MutableStateFlow(AppState(preview = source.preview))
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    /** 应用目录：null=未加载。按需加载（QH-P04-09）——首页/记录不触发全量扫描。 */
    private val appsFlow = MutableStateFlow<List<AppEntry>>(emptyList())
    private var appsRequested = false

    init {
        scope.launch {
            for (task in mutations) try { task() } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = "保存失败：${e.message ?: "未知错误"}"
                _state.update { it.copy(error = message) }
                _messages.tryEmit(message)
            }
        }
        scope.launch {
            combine(dataStore.data.catch { error ->
                if (error is IOException) { _messages.tryEmit("设置读取失败，使用默认值。"); emit(emptyPreferences()) }
                else throw error
            }, source.availability, appsFlow, recordStore?.all ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList()),
                observeStore?.all ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
            { prefs, available, apps, realRecords, observations ->
                val decoded = runCatching { prefs[settingsKey]?.let { SettingsCodec.decode(it) } ?: source.defaultSettings }
                // QH-P08-07：真实执行结果（standard）映射进记录页/摘要；preview 维持示例隔离
                val realProtectionRecords = realRecords.map { r ->
                    top.hnwen17.guard.core.ProtectionRecord(
                        id = r.atEpochMs, appId = r.packageName, appName = r.packageName.substringAfterLast('.'),
                        capability = top.hnwen17.guard.core.Capability.entries.firstOrNull { it.name == r.capability }
                            ?: top.hnwen17.guard.core.Capability.CLEANER, // QH-P18：按记录能力分类，不再一律归广告净化
                        timestamp = r.atEpochMs,
                        outcome = if (r.outcome == top.hnwen17.guard.core.records.ProtectionOutcome.FAILED)
                            top.hnwen17.guard.core.Outcome.OBSERVED else top.hnwen17.guard.core.Outcome.CLOSED,
                        detail = if (r.outcome == top.hnwen17.guard.core.records.ProtectionOutcome.FAILED)
                            "拦截失败：规则 ${r.ruleId} 点击未生效（可在设置停用）"
                        else "规则 ${r.ruleId} v${r.ruleVersion} 自动处理",
                        sample = false
                    )
                }
                val baseRecords = if (prefs[clearedKey] == true) emptyList() else source.records.take(120)
                // QH-P18：观察条目 → 「识别到广告窗口」记录（OBSERVED 不计入统计口径，用户可见引擎所见）
                val observeRecords = if (source.preview) emptyList() else observations.map { o ->
                    top.hnwen17.guard.core.ProtectionRecord(
                        id = o.atEpochMs + o.packageName.hashCode(),
                        appId = o.packageName,
                        appName = o.packageName.substringAfterLast('.'),
                        capability = top.hnwen17.guard.core.Capability.CLEANER,
                        timestamp = o.atEpochMs,
                        outcome = top.hnwen17.guard.core.Outcome.OBSERVED,
                        detail = "识别到疑似广告窗口 · 待规则适配",
                        sample = false
                    )
                }
                val merged = (if (source.preview) baseRecords else realProtectionRecords + observeRecords + baseRecords)
                    .sortedByDescending { it.timestamp }.take(120)
                val cleared = prefs[clearedKey] == true
                android.util.Log.d("GuardState", "combine: available=$available enabled=${decoded.getOrDefault(source.defaultSettings).enabled}")
                AppState(false, source.preview, apps, decoded.getOrDefault(source.defaultSettings), available,
                    merged,
                    if (decoded.isFailure) "本地设置格式错误，当前展示默认值。" else null,
                    if (cleared) null else source.summaryToday,
                    if (cleared) emptyMap() else source.appRecordCounts)
            }.collect { _state.value = it }
        }
    }

    /** 应用页/详情页首次进入时调用一次；重复调用为空操作。IO 在仓库协程，不阻塞调用方。 */
    fun ensureAppsLoaded() {
        if (appsRequested) return
        appsRequested = true
        scope.launch {
            val apps = try { source.loadApplications(application) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                _messages.tryEmit("应用列表读取失败，请稍后重新打开。")
                emptyList()
            }
            appsFlow.value = apps
        }
    }
    private fun enqueue(task: suspend () -> Unit) {
        if (!mutations.trySend(task).isSuccess) {
            _state.update { it.copy(error = "操作较多，请稍后重试。") }
            _messages.tryEmit("操作较多，请稍后重试。")
        }
    }
    fun update(transform: (Settings) -> Settings) = enqueue {
        dataStore.edit { prefs ->
            val current = runCatching { prefs[settingsKey]?.let { SettingsCodec.decode(it) } ?: source.defaultSettings }.getOrDefault(source.defaultSettings)
            prefs[settingsKey] = SettingsCodec.encode(transform(current))
        }
    }
    fun setCapability(cap: Capability, enabled: Boolean, appId: String? = null) = update { current ->
        if (appId == null) current.copy(enabled = toggle(current.enabled, cap, enabled))
        else {
            val policy = current.policies[appId] ?: AppPolicy()
            current.copy(policies = current.policies + (appId to policy.copy(enabled = toggle(policy.enabled, cap, enabled))))
        }
    }
    fun setStrength(appId: String, strength: Strength) = update { current ->
        current.copy(policies = current.policies + (appId to (current.policies[appId] ?: AppPolicy()).copy(strength = strength)))
    }
    fun restoreApp(appId: String) = update { it.copy(policies = it.policies - appId + source.defaultSettings.policies.filterKeys { id -> id == appId }) }
    fun clearRecords() = enqueue { dataStore.edit { it[clearedKey] = true } }
    fun reset() = enqueue { dataStore.edit { it.clear() }; if (source.preview) source.previewScenario(0) }
    fun previewScenario(value: Int) { if (source.preview) source.previewScenario(value) }
}
