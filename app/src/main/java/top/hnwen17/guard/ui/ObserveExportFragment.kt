package top.hnwen17.guard.ui

import android.content.Intent
import android.content.pm.PackageManager
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
import top.hnwen17.guard.databinding.FragmentObserveExportBinding
import top.hnwen17.guard.databinding.ItemAppBinding
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * QH-P18 观察日志导出页（用户设计：与应用列表页同款样式与交互——
 * 品牌头部 + 分段页签（按应用/按时间）+ item_app 行（真实应用名与图标）+ 全选 + 底部导出）。
 */
class ObserveExportFragment : Fragment() {

    private var _binding: FragmentObserveExportBinding? = null
    private val binding get() = _binding!!
    private val appChecks = mutableMapOf<String, CheckBox>()
    private val timeChecks = mutableListOf<Pair<CheckBox, Long>>() // checkbox → 范围起点（0=全部）
    private var lastExportAtMs = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentObserveExportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireContext().applicationContext as top.hnwen17.guard.GuardApplication
        val pm = requireContext().packageManager
        val prefs = requireContext().getSharedPreferences("observe_export", android.content.Context.MODE_PRIVATE)
        lastExportAtMs = prefs.getLong("lastExportAtMs", 0L)
        val observations = app.observeStore.all.value
        val protections = app.recordStore.all.value // QH-P18：与守护记录页同源（防护执行记录）
        val grouped = (observations.map { it.packageName } + protections.map { it.packageName })
            .toSet().map { id -> id to (observations.count { it.packageName == id } + protections.count { it.packageName == id }) }
            .toMap(LinkedHashMap())
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        binding.back.setOnClickListener { parentFragmentManager.popBackStack() }

        // 分段页签：复制应用页选中样式代码
        fun selectTab(byApp: Boolean) {
            listOf(binding.tabByApp to byApp, binding.tabByTime to !byApp).forEach { (tab, selected) ->
                tab.isSelected = selected
                tab.setBackgroundResource(if (selected) R.drawable.bg_segment_selected else 0)
                tab.setTextColor(ContextCompat.getColor(requireContext(), if (selected) R.color.ink else R.color.sub))
                tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
            binding.appListScroll.isVisible = byApp
            binding.timeListScroll.isVisible = !byApp
            binding.selectAllBar.isVisible = byApp
        }
        binding.tabByApp.setOnClickListener { selectTab(true) }
        binding.tabByTime.setOnClickListener { selectTab(false) }
        selectTab(true)

        // 按应用：真实应用名 + 图标 + 记录条数（item_app 行同款）
        for ((pkg, count) in grouped.entries.sortedByDescending { it.value }) {
            val row = ItemAppBinding.inflate(layoutInflater)
            val label = try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() } catch (_: Exception) { pkg.substringAfterLast('.') }
            row.name.text = label
            row.status.text = "$count 条"
            val latest = (observations.filter { it.packageName == pkg }.map { it.atEpochMs } +
                protections.filter { it.packageName == pkg }.map { it.atEpochMs }).maxOrNull() ?: 0L
            val recN = protections.count { it.packageName == pkg }; val obsN = observations.count { it.packageName == pkg }
            row.description.text = "最近 ${fmt.format(Date(latest))} · 防护 $recN 条 · 拦截失败 $obsN 条"
            try { row.icon.setImageDrawable(pm.getApplicationIcon(pkg)) } catch (_: Exception) { }
            row.chevron.isVisible = false
            val check = CheckBox(requireContext()).apply {
                isChecked = true
                contentDescription = "选择 $label"
            }
            row.root.addView(check, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            check.setOnCheckedChangeListener { _, _ -> syncSelectAll() }
            binding.appList.addView(row.root)
            appChecks[pkg] = check
        }
        if (grouped.isEmpty()) {
            binding.appList.addView(TextView(requireContext()).apply {
                text = "暂无记录——引擎尚未看到疑似广告窗口"
                setPadding(0, 40, 0, 0)
            })
        }
        binding.selectAll.setOnCheckedChangeListener { _, checked -> appChecks.values.forEach { it.isChecked = checked } }

        // 按时间：三档范围单选（同款行样式，图标用记录图标）
        val ranges = listOf(
            Triple("自上次导出以来", if (lastExportAtMs > 0) "上次导出于 ${fmt.format(Date(lastExportAtMs))}" else "暂无上次导出，等同全部", lastExportAtMs),
            Triple("今天", "当日 00:00 起", dayStart),
            Triple("全部", "所有观察记录", 0L)
        )
        var checkedTime: CheckBox? = null
        for ((idx, range) in ranges.withIndex()) {
            val count = observations.count { it.atEpochMs >= range.third }
            val row = ItemAppBinding.inflate(layoutInflater)
            row.name.text = range.first
            row.status.text = "$count 条"
            row.description.text = range.second
            row.icon.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_records))
            row.chevron.isVisible = false
            val check = CheckBox(requireContext()).apply { isChecked = idx == 0 }
            row.root.addView(check, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            check.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    checkedTime?.let { if (it !== check) it.isChecked = false }
                    checkedTime = check
                } else if (checkedTime === check) {
                    checkedTime = null
                }
            }
            binding.timeList.addView(row.root)
            timeChecks.add(check to range.third)
        }

        binding.exportBtn.setOnClickListener { export(app, prefs) }
    }

    private fun syncSelectAll() {
        binding.selectAll.isChecked = appChecks.values.all { it.isChecked }
    }

    private fun export(app: top.hnwen17.guard.GuardApplication, prefs: android.content.SharedPreferences) {
        val all = app.observeStore.all.value
        val recs = app.recordStore.all.value
        val since = if (binding.appListScroll.isVisible) 0L else timeChecks.firstOrNull { it.first.isChecked }?.second ?: 0L
        val inRange: (Long) -> Boolean = { since <= 0L || it >= since }
        val chosenPkgs = appChecks.filterValues { it.isChecked }.keys
        val byPkg: (String) -> Boolean = { binding.timeListScroll.isVisible || it in chosenPkgs }
        val selectedRecs = recs.filter { inRange(it.atEpochMs) && byPkg(it.packageName) }
        val selectedObs = all.filter { inRange(it.atEpochMs) && byPkg(it.packageName) }
        val selected = selectedRecs.size + selectedObs.size
        if (selected == 0) {
            android.app.AlertDialog.Builder(requireContext()).setTitle("没有可选记录")
                .setMessage("所选范围内暂无记录。").setPositiveButton("知道了", null).show()
            return
        }
        // QH-P18 用户要求：导出时可选内容范围（全部 / 仅防护记录 / 仅拦截失败记录）
        android.app.AlertDialog.Builder(requireContext()).setTitle("导出内容")
            .setItems(arrayOf("全部（防护记录 + 拦截失败）", "仅防护记录（已关闭/已保护）", "仅拦截失败记录")) { _, which ->
                val recs = if (which == 2) emptyList() else selectedRecs
                val obs = if (which == 1) emptyList() else selectedObs
                share(app, prefs, recs, obs)
            }.show()
    }

    private fun share(
        app: top.hnwen17.guard.GuardApplication,
        prefs: android.content.SharedPreferences,
        selectedRecs: List<top.hnwen17.guard.data.records.RecordStore.CleanerRecord>,
        selectedObs: List<top.hnwen17.guard.data.records.ObserveStore.Observation>
    ) {
        val text = buildString {
            append("轻护记录导出 v${top.hnwen17.guard.BuildConfig.VERSION_NAME}（本机生成，仅供规则适配；防护 ${selectedRecs.size} 条 + 拦截失败 ${selectedObs.size} 条，同窗口重复已合并）\n")
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            if (selectedRecs.isNotEmpty()) {
                append("\n== 防护记录（已执行动作）==")
                for (r in selectedRecs.sortedByDescending { it.atEpochMs }) {
                    append("\n[${fmt.format(Date(r.atEpochMs))}] ${r.packageName} ${r.outcome.name}")
                    append("\n  rule: ${r.ruleId} v${r.ruleVersion} capability=${r.capability}")
                }
            }
            if (selectedObs.isNotEmpty()) {
                append("\n\n== 拦截失败记录（识别到疑似广告窗口）==")
                for (o in selectedObs.sortedByDescending { it.atEpochMs }) {
                    val reasonCn = if (o.reason == "miss") "无可用规则" else "点击未生效"
                    append("\n[${fmt.format(Date(o.atEpochMs))}] ${o.packageName} 拦截失败（$reasonCn）")
                    if (o.className.isNotEmpty()) append("\n  window: ${o.className}")
                    o.samples.forEach { append("\n  · $it") }
                }
            }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_TITLE, "轻护记录导出")
        prefs.edit().putLong("lastExportAtMs", java.lang.System.currentTimeMillis()).apply()
        startActivity(Intent.createChooser(send, "导出记录"))
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
