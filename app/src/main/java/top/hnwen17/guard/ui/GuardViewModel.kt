package top.hnwen17.guard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import top.hnwen17.guard.GuardApplication
import top.hnwen17.guard.core.*
import kotlin.system.measureNanoTime

sealed interface AppRow {
    data class Header(val title: String, val sortable: Boolean = false): AppRow
    data class Item(val app: AppEntry, val policy: AppPolicy, val status: Availability, val groupPosition: Int = 0): AppRow
}
data class DiagnosticState(val busy: Boolean = false, val result: String = "尚未执行计算诊断")

class GuardViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val repository = (application as GuardApplication).repository
    val recordStore = (application as GuardApplication).recordStore // QH-P08-07：真实防护结果
    val runtime = (application as GuardApplication).ruleRuntime // QH-P08-06 诊断入口
    val state = repository.state
    val messages = repository.messages
    val query = saved.getStateFlow("query", "")
    val sort = saved.getStateFlow("sort", "NAME")
    val systemApps = saved.getStateFlow("systemApps", false)
    val sortApplied = saved.getStateFlow("sortApplied", false)
    val type = saved.getStateFlow("type", "ALL")
    val appFilter = saved.getStateFlow("appFilter", "")
    val todayOnly = saved.getStateFlow("todayOnly", false)
    private val pageLimit = saved.getStateFlow("pageLimit", 30)
    val appRows = combine(state, query, sort, systemApps, sortApplied) { s, q, sorting, system, applied ->
        val catalog = s.apps.filter { it.system == system }
        val apps = if (s.preview && q.isBlank() && !applied) catalog
            else filterApps(catalog, q, AppSort.valueOf(sorting), s.settings)
        buildList<AppRow> {
            val recommended = if (q.isBlank() && !system && sorting == "NAME") apps.filter { it.recommended } else emptyList()
            if (recommended.isNotEmpty()) {
                add(AppRow.Header("推荐保护"))
                recommended.forEach { add(it.row(s).copy(groupPosition = -1)) }
            }
            val remaining = apps.filter { it !in recommended }
            if (remaining.isNotEmpty()) {
                add(AppRow.Header(if (q.isNotBlank()) "搜索结果 · ${apps.size} 个" else if(system) "系统应用" else "全部应用", true))
                remaining.forEachIndexed { index, app ->
                    val position = when { remaining.size == 1 -> 0; index == 0 -> 1; index == remaining.lastIndex -> 3; else -> 2 }
                    add(app.row(s).copy(groupPosition = position))
                }
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private fun AppEntry.row(state: AppState): AppRow.Item {
        val enabled = state.policy(id).enabled
        val statuses = enabled.map { state.effective(it, id) }
        val status = when {
            enabled.isEmpty() || state.settings.paused -> Availability.DISABLED
            statuses.any { it == Availability.ACTIVE } -> Availability.ACTIVE
            statuses.any { it == Availability.LIMITED } -> Availability.LIMITED
            statuses.any { it == Availability.NEEDS_PERMISSION } -> Availability.NEEDS_PERMISSION
            statuses.any { it == Availability.UNCONNECTED } -> Availability.UNCONNECTED
            statuses.any { it == Availability.ERROR } -> Availability.ERROR
            else -> Availability.DISABLED
        }
        return AppRow.Item(this, state.policy(id), status)
    }
    private val filteredRecords = combine(state, type, appFilter, todayOnly) { s, t, a, today ->
        filterRecords(s.records, Capability.entries.find { it.name == t }, a.ifEmpty { null }, today)
    }.flowOn(Dispatchers.Default)
    val records = combine(filteredRecords, pageLimit) { items, limit -> items.take(limit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _diagnostics = MutableStateFlow(DiagnosticState())
    val diagnostics = _diagnostics.asStateFlow()
    private var diagnosticJob: Job? = null
    fun ensureAppsLoaded() = repository.ensureAppsLoaded() // QH-P04-09
    fun search(value: String) { saved["query"] = value }
    fun sort(value: AppSort) { saved["sort"] = value.name; saved["sortApplied"] = true }
    fun showSystemApps(value: Boolean) { saved["systemApps"] = value }
    fun filterType(cap: Capability?) { saved["type"] = cap?.name ?: "ALL"; saved["pageLimit"] = 30 }
    fun filterApp(id: String?) { saved["appFilter"] = id ?: ""; saved["pageLimit"] = 30 }
    fun onlyToday(value: Boolean) { saved["todayOnly"] = value; saved["pageLimit"] = 30 }
    fun nextPage() { saved["pageLimit"] = (pageLimit.value + 30).coerceAtMost(120) }
    fun runDiagnostic() {
        if (diagnosticJob?.isActive == true) return
        _diagnostics.value = DiagnosticState(true, "正在后台计算；可关闭弹窗继续切页")
        diagnosticJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                var checksum = 1L
                val elapsed = measureNanoTime {
                    repeat(1_000_000) { i ->
                        if (i % 1024 == 0) ensureActive()
                        checksum = (checksum * 31 + i) xor (checksum ushr 11)
                    }
                }
                "100 万次示例计算完成\n耗时：${elapsed / 1_000_000.0} ms\n校验：${checksum.toULong()}\n这是当前设备的计算诊断，不是广告防护或帧率成绩。"
            }
            _diagnostics.value = DiagnosticState(false, result)
        }
    }
}
