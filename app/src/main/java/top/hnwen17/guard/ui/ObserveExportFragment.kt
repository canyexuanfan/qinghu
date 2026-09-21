package top.hnwen17.guard.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import top.hnwen17.guard.R
import top.hnwen17.guard.core.Availability
import top.hnwen17.guard.core.Capability
import top.hnwen17.guard.core.Outcome
import top.hnwen17.guard.core.ProtectionRecord
import top.hnwen17.guard.databinding.FragmentObserveExportBinding
import top.hnwen17.guard.databinding.ItemAppBinding
import top.hnwen17.guard.databinding.ItemRecordBinding
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * 导出页：按应用/按时间展开折叠查看具体记录。
 * 明细行与记录页同款样式（item_record），每条记录可勾选；导出内容 = 勾选的记录。
 * 全选 / 应用行 / 时间范围行复选框为其下记录的批量勾选入口。
 */
class ObserveExportFragment : Fragment() {

    private var _b: FragmentObserveExportBinding? = null
    private val b get() = _b!!
    private val expandedApps = mutableSetOf<String>()
    private var lastExportAtMs = 0L
    private var allEntries = listOf<ExportEntry>()

    /** 记录级勾选状态（entry key 集合），导出的最终事实；持久化跨进程重建恢复。 */
    private val selected = mutableSetOf<String>()

    /** 同一 entry 在按应用/按时间两个列表各有一个 checkbox，统一刷新。 */
    private val entryChecks = mutableMapOf<String, MutableList<CheckBox>>()
    private val appChecks = linkedMapOf<String, CheckBox>()
    private val appEntryIds = linkedMapOf<String, MutableList<String>>()
    private val timeChecks = mutableListOf<Pair<CheckBox, MutableList<String>>>()
    private var selectAllBox: CheckBox? = null
    private var scopeInt = 0
    private var prefsRef: android.content.SharedPreferences? = null

    /** 程序化 setChecked 时置位，避免联动 listener 级联触发。 */
    private var suppress = false

    private data class ExportEntry(
        val id: Int, val time: Long, val pkg: String,
        val cleaner: top.hnwen17.guard.data.records.RecordStore.CleanerRecord? = null,
        val display: ProtectionRecord? = null,
        val observation: top.hnwen17.guard.data.records.ObserveStore.Observation? = null
    ) {
        /** 跨进程重建稳定的唯一键（时间+包名+类型+序号），用于勾选持久化。
         *  同秒同包同类型的记录（一次开屏触发多条规则）以序号区分，否则勾选联动错乱。 */
        val key: String get() = "$time|$pkg|${if (cleaner != null) "p" else "o"}#$id"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentObserveExportBinding.inflate(inflater, container, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireContext().applicationContext as top.hnwen17.guard.GuardApplication
        val pm = requireContext().packageManager
        val prefs = requireContext().getSharedPreferences("observe_export", android.content.Context.MODE_PRIVATE)
        lastExportAtMs = prefs.getLong("lastExportAtMs", 0L)
        prefsRef = prefs
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 内容范围在进入本页前已选（0=全部 1=仅防护 2=仅拦截失败），列表只展示所选类型
        val scope = arguments?.getInt("scope", 0) ?: 0
        scopeInt = scope
        val scopeLabel = when (scope) { 1 -> "仅防护记录"; 2 -> "仅拦截失败记录"; else -> "全部（防护记录 + 拦截失败）" }
        b.title.text = "导出记录（$scopeLabel）"
        var restoredCount = 0
        b.subtitle.text = if (restoredCount in 1 until allEntries.size)
            "已恢复上次未导出的勾选（$restoredCount 条）；点「全选」可重新全选，生成文本分享给开发者补规则"
        else
            "在筛选后的记录中勾选，生成文本分享给开发者补规则"
        b.back.setOnClickListener { parentFragmentManager.popBackStack() }
        fun selectTab(byApp: Boolean) {
            listOf(b.tabByApp to byApp, b.tabByTime to !byApp).forEach { (tab, sel) ->
                tab.isSelected = sel
                tab.setBackgroundResource(if (sel) R.drawable.bg_segment_selected else 0)
                tab.setTextColor(ContextCompat.getColor(requireContext(), if (sel) R.color.ink else R.color.sub))
                tab.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            }
            b.appListScroll.isVisible = byApp; b.timeListScroll.isVisible = !byApp; b.selectAllBar.isVisible = byApp
        }
        b.tabByApp.setOnClickListener { selectTab(true) }
        b.tabByTime.setOnClickListener { selectTab(false) }
        selectTab(true)

        // 数据源：防护记录 + 观察日志，按进入时选定的内容范围过滤
        fun label(pkg: String) = try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() } catch (_: Exception) { pkg }
        val entries = mutableListOf<ExportEntry>()
        if (scope != 2) {
            for (r in app.recordStore.all.value) {
                // CleanerRecord → ProtectionRecord 展示映射：明细复用记录页同款渲染
                val cap = try { Capability.valueOf(r.capability) } catch (_: Exception) { Capability.CLEANER }
                val outcome = when (r.outcome.name) {
                    "VERIFIED" -> Outcome.CLOSED
                    "EXECUTED" -> Outcome.SENSOR_APPLIED
                    "FAILED" -> Outcome.OBSERVED
                    "START_REJECTED" -> Outcome.START_REJECTED
                    else -> Outcome.CLOSED
                }
                val pr = ProtectionRecord(
                    id = 0, appId = r.packageName, appName = label(r.packageName), capability = cap,
                    timestamp = r.atEpochMs, outcome = outcome,
                    detail = "规则 ${r.ruleId} v${r.ruleVersion} · ${r.capability}", sample = false
                )
                entries.add(ExportEntry(entries.size, r.atEpochMs, r.packageName, cleaner = r, display = pr))
            }
        }
        if (scope != 1) {
            for (o in app.observeStore.all.value) {
                entries.add(ExportEntry(entries.size, o.atEpochMs, o.packageName, observation = o))
            }
        }
        allEntries = entries.sortedByDescending { it.time }
        allEntries = allEntries.mapIndexed { idx, e -> e.copy(id = idx) }
        // 勾选持久化：仅用于进程意外被杀后的页面重建恢复（主动进入时入口已清空存档）。
        // 默认全部不选（用户要求）：避免「默认全选 + 范围重叠」被误解为没选任何东西却导出全部；
        // 用户按需勾选应用/时间范围，按钮实时显示「导出已选 N 条」。
        val savedSel = prefs.getStringSet("selected_$scope", null)
        val allKeys = allEntries.map { it.key }.toSet()
        if (savedSel != null && savedSel.any { it in allKeys }) {
            selected.addAll(savedSel.intersect(allKeys))
            restoredCount = selected.size
        }

        fun label(e: ExportEntry) = label(e.pkg)

        // 按应用分组
        val grouped = allEntries.groupBy { it.pkg }
        for ((pkg, list) in grouped.entries.sortedByDescending { it.value.size }) {
            val row = ItemAppBinding.inflate(layoutInflater)
            row.name.text = label(list.first())
            row.status.text = "${list.size} 条"
            val latest = list.maxOfOrNull { it.time } ?: 0L
            val kinds = list.groupBy { if (it.cleaner != null) "防护" else "未拦截" }.entries.joinToString(" · ") { "${it.key} ${it.value.size}" }
            row.description.text = "最近 ${fmt.format(Date(latest))} · $kinds"
            try { row.icon.setImageDrawable(pm.getApplicationIcon(pkg)) } catch (_: Exception) { }
            row.chevron.isVisible = true
            val check = CheckBox(requireContext())
            row.root.addView(check, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            appChecks[pkg] = check
            val ids = list.map { it.key }.toMutableList()
            appEntryIds[pkg] = ids
            check.setOnCheckedChangeListener { _, isChecked ->
                if (suppress) return@setOnCheckedChangeListener
                if (isChecked) selected.addAll(ids) else selected.removeAll(ids)
                refreshChecks()
            }

            val childContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(48, 0, 12, 8)
            }
            for (e in list.sortedByDescending { it.time }) childContainer.addView(entryRow(e))
            row.root.setOnClickListener {
                // Set.remove 返回 Boolean（是否原本存在）——误写 != null 恒真导致永不展开
                val wasExpanded = expandedApps.remove(pkg)
                if (!wasExpanded) expandedApps.add(pkg)
                childContainer.isVisible = !wasExpanded
            }
            // 行在上、明细在下（addView 顺序决定展开位置）
            b.appList.addView(row.root)
            b.appList.addView(childContainer)
        }

        if (grouped.isEmpty()) {
            b.appList.addView(TextView(requireContext()).apply {
                text = "暂无记录——引擎尚未看到疑似广告窗口"; setPadding(0, 40, 0, 0)
            })
        }

        // 按时间分组（各范围均为「起点之后」；范围间单选互斥：勾一个取代其他，默认不选）
        val ranges = listOf(
            Triple("自上次导出以来", if (lastExportAtMs > 0) "上次导出于 " + fmt.format(Date(lastExportAtMs)) else "暂无上次导出，等同全部", lastExportAtMs),
            Triple("今天", "当日 00:00 起", dayStart),
            Triple("全部", "所有记录", 0L)
        )
        for (range in ranges) {
            val rangeEntries = allEntries.filter { it.time >= range.third }
            val row = ItemAppBinding.inflate(layoutInflater)
            row.name.text = "${range.first}（${rangeEntries.size} 条）"
            row.description.text = range.second
            row.icon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_records))
            row.chevron.isVisible = false
            row.status.isVisible = false // item 布局默认文案「待接入」对时间范围行无意义
            val check = CheckBox(requireContext())
            row.root.addView(check, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val ids = rangeEntries.map { it.key }.toMutableList()
            check.setOnCheckedChangeListener { _, isChecked ->
                if (suppress) return@setOnCheckedChangeListener
                // 单选语义：勾选一个范围即以其取代其他选择（用户心智：选一个时间范围）
                if (isChecked) {
                    selected.clear()
                    selected.addAll(ids)
                } else {
                    selected.removeAll(ids)
                }
                refreshChecks()
            }
            timeChecks.add(check to ids)

            val childContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(48, 0, 12, 8)
            }
            if (rangeEntries.isEmpty()) {
                // 空范围也给出可见反馈，避免「点了没反应」
                childContainer.addView(TextView(requireContext()).apply {
                    text = "该范围暂无记录"; setPadding(16, 12, 16, 12)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.sub))
                })
            }
            for (e in rangeEntries.sortedByDescending { it.time }) childContainer.addView(entryRow(e))
            row.root.setOnClickListener {
                childContainer.visibility = if (childContainer.visibility == View.GONE) View.VISIBLE else View.GONE
            }
            b.timeList.addView(row.root)
            b.timeList.addView(childContainer)
        }

        // 全选：所有记录的批量勾选入口
        selectAllBox = b.selectAll
        b.selectAll.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            if (checked) selected.addAll(allEntries.map { it.key }) else selected.clear()
            refreshChecks()
        }
        refreshChecks()

        b.exportBtn.setOnClickListener { doExport(prefs) }
    }

    /** 记录页同款明细行 + 前置勾选框。 */
    private fun entryRow(e: ExportEntry): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val check = CheckBox(requireContext())
        val item = ItemRecordBinding.inflate(layoutInflater)
        if (e.display != null) {
            showRecord(item, e.display) // 与记录页完全一致的标题/状态/图标/描述
        } else {
            val o = e.observation
            val appName = try {
                requireContext().packageManager.getApplicationInfo(e.pkg, 0).loadLabel(requireContext().packageManager).toString()
            } catch (_: Exception) { e.pkg }
            item.time.text = SimpleDateFormat("HH:mm", Locale.US).format(Date(e.time))
            item.icon.capability(Capability.CLEANER)
            item.title.text = "未拦截"
            item.status.badge(Availability.LIMITED, "未拦截")
            item.description.text = "$appName ${o?.className.orEmpty()}（${if (o?.reason == "miss") "无可用规则" else "点击未生效"}）"
        }
        item.root.setOnClickListener { check.toggle() }
        check.setOnCheckedChangeListener { _, isChecked ->
            if (suppress) return@setOnCheckedChangeListener
            if (isChecked) selected.add(e.key) else selected.remove(e.key)
            refreshChecks()
        }
        entryChecks.getOrPut(e.key) { mutableListOf() }.add(check)
        // 勾选框放行尾，与应用行勾选框同列对齐（行内垂直居中）
        row.addView(item.root, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(check)
        return row
    }

    /** 勾选持久化：进程被杀/页面重建后恢复用户的选择（导出成功后清除）。 */
    private fun persistSelection(prefs: android.content.SharedPreferences) {
        // 必须存副本：存引用时 framework 以 equals 判定"未变化"跳过写盘，持久化失效
        prefs.edit().putStringSet("selected_" + scopeInt, HashSet(selected)).apply()
    }

    /** 依 selected 集合统一校准全部 checkbox（明细 + 应用 + 时间 + 全选）。 */
    private fun refreshChecks() {
        suppress = true
        for ((id, checks) in entryChecks) for (c in checks) c.isChecked = id in selected
        for ((pkg, cb) in appChecks) cb.isChecked = appEntryIds[pkg].orEmpty().all { it in selected }
        for ((cb, ids) in timeChecks) cb.isChecked = ids.all { it in selected }
        selectAllBox?.isChecked = allEntries.all { it.key in selected }
        b.exportBtn.text = "导出已选 ${selected.size} 条（共 ${allEntries.size} 条）"
        prefsRef?.let { persistSelection(it) }
        suppress = false
    }

    private fun doExport(prefs: android.content.SharedPreferences) {
        val scope = arguments?.getInt("scope", 0) ?: 0
        val scopeLabel = when (scope) { 1 -> "仅防护记录"; 2 -> "仅拦截失败记录"; else -> "全部（防护记录 + 拦截失败）" }
        val chosen = allEntries.filter { it.key in selected }
        val recs = chosen.mapNotNull { it.cleaner }
        val obs = chosen.mapNotNull { it.observation }
        if (recs.isEmpty() && obs.isEmpty()) {
            android.app.AlertDialog.Builder(requireContext()).setTitle("没有可选记录")
                .setMessage("所选范围内暂无勾选记录。").setPositiveButton("知道了", null).show()
            return
        }
        // 导出确认：让「已选条数」在动手前再确认一次，防止状态异常时一键导出全部
        android.app.AlertDialog.Builder(requireContext()).setTitle("确认导出")
            .setMessage("即将导出已勾选的 ${chosen.size} 条记录（防护 ${recs.size} + 拦截失败 ${obs.size}）。\n\n若这与你的勾选预期不符，请取消并检查列表中的勾选状态。")
            .setPositiveButton("确认导出") { _, _ -> share(prefs, scopeLabel, recs, obs) }
            .setNegativeButton("取消", null).show()
    }

    private fun share(
        prefs: android.content.SharedPreferences,
        scopeLabel: String,
        recs: List<top.hnwen17.guard.data.records.RecordStore.CleanerRecord>,
        obs: List<top.hnwen17.guard.data.records.ObserveStore.Observation>
    ) {
        val text = buildString {
            append("轻护记录导出 v${top.hnwen17.guard.BuildConfig.VERSION_NAME}（本机生成，仅供规则适配；范围=$scopeLabel；防护 ${recs.size} 条 + 拦截失败 ${obs.size} 条）\n")
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            if (recs.isNotEmpty()) {
                append("\n== 防护记录（已执行动作）==")
                for (r in recs.sortedByDescending { it.atEpochMs }) {
                    append("\n[${fmt.format(Date(r.atEpochMs))}] ${r.packageName} ${r.outcome.name}")
                    append("\n  rule: ${r.ruleId} v${r.ruleVersion} capability=${r.capability}")
                }
            }
            if (obs.isNotEmpty()) {
                append("\n\n== 拦截失败记录（识别到疑似广告窗口）==")
                for (o in obs.sortedByDescending { it.atEpochMs }) {
                    val reasonCn = if (o.reason == "miss") "无可用规则" else "点击未生效"
                    append("\n[${fmt.format(Date(o.atEpochMs))}] ${o.packageName} 拦截失败（$reasonCn）")
                    if (o.className.isNotEmpty()) append("\n  window: ${o.className}")
                    o.samples.forEach { append("\n  · $it") }
                }
            }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_TITLE, "轻护记录导出")
        prefs.edit().remove("selected_" + scopeInt).putLong("lastExportAtMs", java.lang.System.currentTimeMillis()).apply()
        startActivity(Intent.createChooser(send, "导出记录"))
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
